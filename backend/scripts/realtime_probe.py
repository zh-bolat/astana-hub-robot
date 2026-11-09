"""Пробник OpenAI Realtime для робота Курултая (docs/kurultai/realtime-voice.md, этап 1). Не часть сервера.

Запуск внутри контейнера kurultai-api (там .env, снимок quryltai.kz и ключи; в вывод секреты не попадают):
    docker exec -i kurultai-robot-kurultai-api-1 python - < scripts/realtime_probe.py

Что делает:
1. Синтезирует вопросы ru/kk голосом ElevenLabs (те же голоса, что у робота) в PCM16 24 кГц — «посетитель».
2. Для каждого вопроса — новая сессия Realtime с инструкциями Курултая (персона + правила + снимок quryltai.kz),
   звук вопроса целиком, commit, response.create.
3. Меряет: от commit до первого звука ответа и до конца ответа; сохраняет ответ в WAV для прослушивания
   (/tmp/realtime_probe/*.wav) и печатает расшифровку ответа.

Переменные: KURULTAI_OPENAI_API_KEY (обязательно), KURULTAI_REALTIME_MODEL (gpt-realtime-2.1),
KURULTAI_REALTIME_VOICE (cedar).
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import time
import wave
from datetime import UTC, datetime
from pathlib import Path

import httpx
import websockets

from app.config import Settings, secret_text
from app.kurultai import prompts
from app.kurultai.quryltai import ASTANA_TZ, QuryltaiSync

OUT = Path("/tmp/realtime_probe")
RATE = 24_000
QUESTIONS = [
    ("ru", "Акылтай, сколько комитетов в Курултае?"),
    ("ru", "Акылтай, какие законопроекты сейчас на рассмотрении?"),
    ("ru", "Акылтай, кто ты?"),
    ("ru", "Акылтай, как гражданину подать обращение в Курултай?"),
    ("ru", "Акылтай, что такое пленарное заседание?"),
    ("kk", "Ақылтай, Құрылтайда неше комитет бар?"),
    ("kk", "Ақылтай, қазір қандай заң жобалары қаралып жатыр?"),
    ("kk", "Ақылтай, сен кімсің?"),
    ("kk", "Ақылтай, азамат Құрылтайға өтінішті қалай береді?"),
    ("kk", "Ақылтай, пленарлық отырыс деген не?"),
]
REALTIME_NOTE = (
    "\n\nГОЛОСОВОЙ РЕЖИМ (Realtime): ты говоришь вслух сам. Отвечай коротко, 1–3 предложения, без списков и "
    "без тегов эмоций в квадратных скобках. Язык ответа — строго тот, что указан ниже, весь ответ целиком."
)


def instructions(settings: Settings, lang: str) -> str:
    q = QuryltaiSync(settings.quryltai_base_url, 5.0, settings.quryltai_snapshot_path)
    q.load_cache()
    base = prompts.kurultai_instructions(q.prompt_context(), "")
    lang_line = "\nЯзык ответа: " + ("kk (казахский)" if lang == "kk" else "ru (русский)")
    now = datetime.now(UTC).astimezone(ASTANA_TZ)
    return base + REALTIME_NOTE + lang_line + f"\nСейчас в Астане: {now:%Y-%m-%d %H:%M}"


async def synth(settings: Settings, lang: str, text: str) -> bytes:
    voice = settings.elevenlabs_voice_kk if lang == "kk" else settings.elevenlabs_voice_ru
    key = secret_text(settings.elevenlabs_api_key)
    async with httpx.AsyncClient(timeout=30) as c:
        r = await c.post(
            f"{settings.elevenlabs_base_url}/v1/text-to-speech/{voice}?output_format=pcm_24000",
            headers={"xi-api-key": key},
            json={"text": text, "model_id": "eleven_v3" if lang == "kk" else "eleven_flash_v2_5"},
        )
        r.raise_for_status()
        return r.content


def save_wav(path: Path, pcm: bytes) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm)


async def ask(settings: Settings, model: str, voice: str, key: str, lang: str, pcm: bytes) -> dict:
    url = f"wss://api.openai.com/v1/realtime?model={model}"
    out = {"first_audio_s": None, "done_s": None, "transcript": "", "error": None, "audio": b""}
    async with websockets.connect(
        url, additional_headers={"Authorization": f"Bearer {key}"}, max_size=None, open_timeout=15
    ) as ws:
        await ws.send(
            json.dumps(
                {
                    "type": "session.update",
                    "session": {
                        "type": "realtime",
                        "instructions": instructions(settings, lang),
                        "output_modalities": ["audio"],
                        "audio": {
                            "input": {
                                "format": {"type": "audio/pcm", "rate": RATE},
                                "turn_detection": None,
                            },
                            "output": {
                                "format": {"type": "audio/pcm", "rate": RATE},
                                "voice": voice,
                            },
                        },
                    },
                }
            )
        )
        for i in range(0, len(pcm), 48_000):  # ~1 с кусками
            await ws.send(
                json.dumps(
                    {
                        "type": "input_audio_buffer.append",
                        "audio": base64.b64encode(pcm[i : i + 48_000]).decode(),
                    }
                )
            )
        await ws.send(json.dumps({"type": "input_audio_buffer.commit"}))
        t0 = time.perf_counter()
        await ws.send(json.dumps({"type": "response.create"}))
        audio = bytearray()
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), 30))
            kind = msg.get("type", "")
            if kind in ("response.output_audio.delta", "response.audio.delta"):
                if out["first_audio_s"] is None:
                    out["first_audio_s"] = round(time.perf_counter() - t0, 2)
                audio += base64.b64decode(msg["delta"])
            elif kind in (
                "response.output_audio_transcript.delta",
                "response.audio_transcript.delta",
            ):
                out["transcript"] += msg.get("delta", "")
            elif kind == "error":
                out["error"] = (msg.get("error") or {}).get("message", "error")[:200]
                break
            elif kind == "response.done":
                out["done_s"] = round(time.perf_counter() - t0, 2)
                status = (msg.get("response") or {}).get("status")
                if status not in (None, "completed"):
                    out["error"] = f"response {status}"
                break
        out["audio"] = bytes(audio)
    return out


async def main() -> None:
    settings = Settings()
    key = os.environ.get("KURULTAI_OPENAI_API_KEY", "").strip()
    if not key:
        print("Нет KURULTAI_OPENAI_API_KEY в .env сервера — пробник не запущен.")
        return
    model = os.environ.get("KURULTAI_REALTIME_MODEL", "gpt-realtime-2.1")
    voice = os.environ.get("KURULTAI_REALTIME_VOICE", "cedar")
    OUT.mkdir(parents=True, exist_ok=True)
    print(
        f"модель {model}, голос {voice}, инструкции ~{len(instructions(settings, 'ru')) // 1000} тыс. знаков"
    )
    for n, (lang, text) in enumerate(QUESTIONS, 1):
        try:
            pcm = await synth(settings, lang, text)
            save_wav(OUT / f"{n:02d}-{lang}-question.wav", pcm)
            res = await ask(settings, model, voice, key, lang, pcm)
        except Exception as exc:  # пробник: показываем и идём дальше
            print(f"{n:02d} {lang} ОШИБКА {type(exc).__name__}: {str(exc)[:160]}")
            continue
        if res["audio"]:
            save_wav(OUT / f"{n:02d}-{lang}-answer.wav", res["audio"])
        print(
            f"{n:02d} {lang} первый звук {res['first_audio_s']} с, весь ответ {res['done_s']} с"
            f"{' ОШИБКА ' + res['error'] if res['error'] else ''}\n   В: {text}\n   О: {res['transcript'][:300]}"
        )


asyncio.run(main())
