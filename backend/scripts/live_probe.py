"""Пробник живого режима робота Курултая сквозь наш сервер: «робот» из скрипта → WS /v1/live → ответ.

Что меряет (по каждому вопросу): от конца речи посетителя до `user_final` (распознавание), до первого байта звука
(то, что слышит человек), до `turn_end`; печатает ответ (субтитры `phrase`) — на глаз видно, нашёл ли робот факт.
Запуск — внутри контейнера api на сервере (ключи из его окружения, в вывод не попадают):

    cd backend && ./scripts/run_live_probe.sh [ru|kk|all] [N повторов]

Вопросы озвучивает ElevenLabs (Dmitry, pcm_16000) — один раз на вопрос, ~100 знаков → ~100 кредитов; при остатке
меньше MIN_CREDITS пробник не запускается. Не часть сервера; протокол — docs/astana-hub/api-contract.md, `WS /v1/live`.
"""

from __future__ import annotations

import asyncio
import json
import os
import statistics
import sys
import time
import uuid

import httpx
import websockets

from app.config import Settings, secret_text

RATE = 16_000
FRAME_MS = 40
FRAME = RATE * 2 * FRAME_MS // 1000  # байт в кадре
SILENCE = bytes(FRAME)
MIN_CREDITS = 25_000
ANSWER_TIMEOUT_S = 25.0
PAUSE_AFTER_S = 1.5  # тишина после ответа, прежде чем задать следующий вопрос

QUESTIONS = [
    ("ru", "Сколько комитетов в Курултае?"),
    ("ru", "Кто докладывает по закону об альтернативной энергетике на пленарном заседании?"),
    ("ru", "Что будет на пленарном заседании 11 ноября?"),
    ("ru", "Что такое Правительственный час?"),
    ("ru", "Какие законопроекты сейчас на рассмотрении?"),
    ("ru", "Как гражданину подать обращение в Курултай?"),
    ("ru", "Кто председатель комитета по международным делам?"),
    ("ru", "Какая сегодня погода в Астане?"),
    ("kk", "Құрылтайда неше комитет бар?"),
    ("kk", "Он бірінші қарашадағы пленарлық отырыста не қаралады?"),
    ("kk", "Қазір қандай заң жобалары қаралып жатыр?"),
    ("kk", "Азамат Құрылтайға өтінішті қалай береді?"),
]


async def credits(settings: Settings) -> int:
    async with httpx.AsyncClient(timeout=20) as c:
        r = await c.get(
            f"{settings.elevenlabs_base_url}/v1/user/subscription",
            headers={"xi-api-key": secret_text(settings.elevenlabs_api_key)},
        )
        r.raise_for_status()
        d = r.json()
        return int(d.get("character_limit", 0)) - int(d.get("character_count", 0))


async def synth(settings: Settings, lang: str, text: str) -> bytes:
    voice = settings.elevenlabs_voice_kk if lang == "kk" else settings.elevenlabs_voice_ru
    async with httpx.AsyncClient(timeout=30) as c:
        r = await c.post(
            f"{settings.elevenlabs_base_url}/v1/text-to-speech/{voice}?output_format=pcm_16000",
            headers={"xi-api-key": secret_text(settings.elevenlabs_api_key)},
            json={"text": text, "model_id": "eleven_v3" if lang == "kk" else "eleven_flash_v2_5"},
        )
        r.raise_for_status()
        pcm = r.content
        return pcm if len(pcm) % 2 == 0 else pcm[:-1]


class Turn:
    def __init__(self, lang: str, text: str) -> None:
        self.lang, self.text = lang, text
        self.speech_end: float | None = None
        self.final_ms: int | None = None
        self.final_text = ""
        self.first_audio_ms: int | None = None
        self.turn_end_ms: int | None = None
        self.answer: list[str] = []
        self.ignored = ""
        self.error = ""
        self.done = asyncio.Event()

    def since(self) -> int:
        return int((time.perf_counter() - (self.speech_end or time.perf_counter())) * 1000)


async def ask_all(settings: Settings, lang_filter: str, repeats: int) -> list[Turn]:
    url = os.environ.get("LIVE_PROBE_URL", "ws://127.0.0.1:8000/v1/live")
    token = os.environ.get("KURULTAI_ROBOT_TOKEN") or secret_text(settings.kurultai_robot_token)
    questions = [q for q in QUESTIONS if lang_filter in ("all", q[0])] * repeats
    clips: dict[str, bytes] = {}
    for lang, text in questions:
        if text not in clips:
            clips[text] = await synth(settings, lang, text)
    turns: list[Turn] = []
    for lang in sorted({q[0] for q in questions}):
        async with websockets.connect(
            f"{url}?session_id=probe-{uuid.uuid4()}&lang={lang}",
            additional_headers={"Authorization": f"Bearer {token}"},
            max_size=None,
        ) as ws:
            turns += await run_session(ws, lang, [q[1] for q in questions if q[0] == lang], clips)
    return turns


class Robot:
    """Состояние «робота» в одной сессии: какой ход сейчас идёт — к нему относятся кадры сервера."""

    def __init__(self) -> None:
        self.current: Turn | None = None

    async def read(self, ws) -> None:
        async for msg in ws:
            t = self.current
            if isinstance(msg, bytes):
                if t is not None and t.speech_end and t.first_audio_ms is None:
                    t.first_audio_ms = t.since()
                continue
            ev = json.loads(msg)
            kind = ev.get("type")
            if t is None or not t.speech_end:
                continue
            if kind == "user_final":
                t.final_ms, t.final_text = t.since(), ev.get("text", "")
            elif kind == "phrase":
                t.answer.append(ev.get("text", ""))
            elif kind == "ignored":
                t.ignored = ev.get("reason", "ignored")
                t.done.set()
            elif kind == "turn_end":
                t.turn_end_ms = t.since()
                t.done.set()
            elif kind == "error":
                t.error = json.dumps(ev.get("error"), ensure_ascii=False)
                t.done.set()


async def _silence(ws, seconds: float) -> None:
    end = time.perf_counter() + seconds
    while time.perf_counter() < end:
        await ws.send(SILENCE)
        await asyncio.sleep(FRAME_MS / 1000)


async def run_session(ws, lang: str, texts: list[str], clips: dict[str, bytes]) -> list[Turn]:
    await ws.send(json.dumps({"type": "hello", "lang": lang, "place_id": None, "aec": True}))
    robot = Robot()
    reader_task = asyncio.create_task(robot.read(ws))
    turns: list[Turn] = []
    await _silence(ws, 1.5)  # прогрев сессии (как у робота после тапа)
    for text in texts:
        t = Turn(lang, text)
        robot.current = t
        pcm = clips[text]
        started = time.perf_counter()
        for i in range(0, len(pcm), FRAME):  # речь — в реальном времени, кадрами по 40 мс
            frame = pcm[i : i + FRAME]
            await ws.send(frame if len(frame) == FRAME else frame + bytes(FRAME - len(frame)))
            await asyncio.sleep(
                max(0.0, started + (i // FRAME + 1) * FRAME_MS / 1000 - time.perf_counter())
            )
        t.speech_end = time.perf_counter()
        while not t.done.is_set() and t.since() < ANSWER_TIMEOUT_S * 1000:  # тишина с микрофона
            await ws.send(SILENCE)
            await asyncio.sleep(FRAME_MS / 1000)
        if not t.done.is_set():
            t.error = t.error or f"нет turn_end за {ANSWER_TIMEOUT_S:.0f} с"
        turns.append(t)
        print(fmt(t), flush=True)
        await _silence(ws, PAUSE_AFTER_S)
        robot.current = None
    reader_task.cancel()
    return turns


def fmt(t: Turn) -> str:
    ms = lambda v: "—" if v is None else f"{v / 1000:.2f}с"  # noqa: E731
    head = f"[{t.lang}] {t.text}\n    услышал: «{t.final_text}» ({ms(t.final_ms)})"
    if t.ignored:
        return head + f"\n    ПРОПУЩЕНО: {t.ignored}"
    if t.error and t.first_audio_ms is None:
        return head + f"\n    ОШИБКА: {t.error}"
    answer = " ".join(" ".join(t.answer).split())
    return (
        head
        + f"\n    первый звук {ms(t.first_audio_ms)}, конец {ms(t.turn_end_ms)}\n    ответ: {answer}"
    )


def pct(values: list[int], p: float) -> str:
    if not values:
        return "—"
    values = sorted(values)
    k = min(len(values) - 1, max(0, round(p * (len(values) - 1))))
    return f"{values[k] / 1000:.2f}с"


async def main() -> None:
    settings = Settings()
    lang_filter = sys.argv[1] if len(sys.argv) > 1 else "all"
    repeats = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    left = await credits(settings)
    print(f"ElevenLabs: осталось {left} кредитов")
    if left < MIN_CREDITS:
        print(f"меньше {MIN_CREDITS} — пробник не запускаем")
        return
    turns = await ask_all(settings, lang_filter, repeats)
    fa = [t.first_audio_ms for t in turns if t.first_audio_ms is not None]
    fin = [t.final_ms for t in turns if t.final_ms is not None]
    end = [t.turn_end_ms for t in turns if t.turn_end_ms is not None]
    print(
        f"\nИТОГО {len(turns)} вопросов: ответов {len(fa)}, пропущено {sum(1 for t in turns if t.ignored)}, "
        f"ошибок {sum(1 for t in turns if t.error)}"
    )
    print(f"распознавание: p50 {pct(fin, 0.5)} p95 {pct(fin, 0.95)}")
    print(f"первый звук:   p50 {pct(fa, 0.5)} p95 {pct(fa, 0.95)} max {pct(fa, 1.0)}"
          + (f" среднее {statistics.mean(fa) / 1000:.2f}с" if fa else ""))  # fmt: skip
    print(f"конец ответа:  p50 {pct(end, 0.5)} p95 {pct(end, 0.95)}")


if __name__ == "__main__":
    asyncio.run(main())
