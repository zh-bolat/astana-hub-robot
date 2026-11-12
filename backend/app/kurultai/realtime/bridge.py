"""Мост «робот Курултая ↔ OpenAI Realtime» (docs/kurultai/realtime-voice.md).

Робот говорит с сервером по прежнему протоколу `WS /v1/live` (docs/api-contract.md): микрофон PCM16 16 кГц, команды
`hello` / `visitor_arrived` / `interrupt` / `playback_done`; сервер шлёт `ready`, `listening`, `user_speaking`,
`user_final`, `ignored`, `thinking`, `turn_start`, `phrase`, `turn_end`, `interrupted` и звук PCM16 24 кГц.
Приложение робота не меняется: меняется только то, что за сервером.

Внутри — одна сессия OpenAI Realtime на соединение робота:
- микрофон → 24 кГц → `input_audio_buffer.append`; конец фразы находит VAD OpenAI (`server_vad`), ответ сам не
  создаётся (`create_response: false`), речь ответ не перебивает (`interrupt_response: false`);
- решение сервера по расшифровке фразы: робот говорит — только «стоп» отменяет ответ, остальное (эхо, чужая речь)
  удаляется; обращение «Ақылтай / робот / бот» или окно разговора 30 с (`app/kurultai/wake.py` через чат-сервис
  Курултая) → `response.create`, иначе фраза удаляется и робот получает `ignored`. Быстрый путь: внутри окна
  разговора ответ стартует сразу по `input_audio_buffer.committed`, не дожидаясь расшифровки;
- **ответ голосом** (KURULTAI_REALTIME_OUTPUT):
  - `text` (по умолчанию, «гибрид»): модель пишет текст потоком, сервер режет его на фразы и озвучивает каждую
    голосом робота ElevenLabs Dmitry (потоком PCM 24 кГц) — без акцента голосов OpenAI;
  - `voice`: модель говорит сама голосом OpenAI (быстрее на ~0,3 с, но с акцентом в ru/kk — решение 09.11: нет);
- `web_search` → справка из интернета (`websearch.py`) → `function_call_output` → продолжение того же ответа.
Инструкции — та же база Курултая (`instructions.py`); язык — с экрана (`hello.lang`), голос — на языке экрана.

Навигация (docs/kurultai/navigation.md): инструмент `guide_to(place_id)` → `action guide_to` роботу после
`playback_done` хода (или через KURULTAI_GUIDE_PLAYBACK_TIMEOUT_S после `turn_end`); в поездке (`state
navigating:true`) реплики не отвечаются (`ignored`, reason navigating), «стоп» / `interrupt` → `action
stop_navigation`; после `navigating:false` сервер сам говорит итог новым ходом (кроме авто-возврата на пост,
`auto:true`). Предложение проводить (`app/kurultai/offers.py`) — дополнение к `response.create` приветствия или
второго ответа; `response.instructions` заменяет инструкции сессии, поэтому уходят полные инструкции + дополнение.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import json
import re
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from datetime import UTC, datetime
from typing import Any

import numpy as np
import websockets
from fastapi import WebSocket
from starlette.websockets import WebSocketState

from app.config import Settings, secret_text
from app.kurultai.offers import OfferPolicy
from app.kurultai.places import KurultaiPlaces
from app.kurultai.quryltai import ASTANA_TZ
from app.kurultai.rag.knowledge import TOOL as SEARCH_TOOL
from app.kurultai.realtime import websearch
from app.kurultai.realtime.health import FATAL_CODES
from app.kurultai.realtime.instructions import (
    GUIDE_TOOL,
    greeting_addition,
    navigation_addition,
    offer_addition,
    realtime_instructions,
    realtime_instructions_rag,
)
from app.lang import Lang, normalize_lang
from app.live.protocol import AUDIO_FORMAT, MAX_AUDIO_FRAME, MAX_TEXT_FRAME, event, new_turn_id
from app.live.stop import is_stop_command, stop_request
from app.logging_setup import logger, new_request_id
from app.speech.base import has_letters
from app.tts_text import normalize_for_tts

IN_RATE = 16_000  # микрофон робота
OA_RATE = 24_000  # вход OpenAI Realtime и звук ответа роботу (pcm_s16le_24000)
SEND_CHUNK = OA_RATE * 2 // 10  # копим 100 мс перед отправкой в OpenAI
# Подсказку распознаванию не даём: на шуме и неразборчивой речи gpt-4o-transcribe выдавал её текстом
# («Ақылтай, Акылтай, Құрылтай, Курултай, робот…» в субтитрах, журнал 09.11 17:02–17:06) — и робот на это отвечал.
# Короткие осмысленные реплики, на которые отвечаем даже одним словом; другое одно слово — шум (_is_noise).
SHORT_OK = frozenset(
    [
        "привет",
        "здравствуйте",
        "здравствуй",
        "спасибо",
        "да",
        "нет",
        "ага",
        "пока",
        "стоп",
        "хорошо",
        "понятно",
        "ясно",
        "ок",
        "окей",
        "сәлем",
        "сәлеметсіз",
        "рахмет",
        "иә",
        "жоқ",
        "жарайды",
        "түсінікті",
        "сау",
        "hello",
        "hi",
        "thanks",
        "yes",
        "no",
    ]
)
_SENTENCE = re.compile(r"(.+?[.!?…])(\s+|$)", re.S)
_EMOTION = re.compile(r"^\s*\[(\w+)\]\s*")
EMOTIONS = frozenset(
    {"neutral", "happy", "thinking", "surprised", "sad", "excited"}
)  # лица робота (face.js)
_WORDS = re.compile(r"\w+", re.U)
_CLAUSE = re.compile(
    r"(.{25,}?[,;:—])(\s+)", re.S
)  # первая фраза ответа: режем по запятой — голос раньше

# Детектор речи в режиме noisy (docs/kurultai/robot-settings.md): стартовые пороги, подбирать по логам шумного дня
NOISY_VAD = {"threshold": 0.7, "prefix_padding_ms": 300, "silence_duration_ms": 600}

Connector = Callable[..., Awaitable[Any]]
TtsStream = Callable[[str, Lang], AsyncIterator[bytes]]


def resample_16_to_24(pcm: bytes) -> bytes:
    """PCM16 mono 16 кГц → 24 кГц (линейная интерполяция; для распознавания и VAD этого достаточно)."""
    x = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype="<i2").astype(np.float32)
    if x.size == 0:
        return b""
    n = x.size * 3 // 2
    y = np.interp(
        np.arange(n, dtype=np.float32) * (2.0 / 3.0), np.arange(x.size, dtype=np.float32), x
    )
    return np.clip(np.round(y), -32768, 32767).astype("<i2").tobytes()


def elevenlabs_provider(settings: Settings) -> Any:
    """Провайдер голоса робота (Dmitry, как в live-пайплайне: модели, голоса и настройки из .env), PCM 24 кГц.
    Один на сервер (main.py → app.state.realtime_tts), закрывается в lifespan; у провайдера есть aclose()."""
    key = secret_text(settings.elevenlabs_api_key)
    if not key:
        return None
    from app.live.services import live_tts_model, live_voices, voice_settings
    from app.speech.elevenlabs import ElevenLabsProvider

    return ElevenLabsProvider(
        key,
        base_url=settings.elevenlabs_base_url,
        voices=live_voices(settings),
        tts_model_kk=live_tts_model(settings, "kk"),
        tts_model_default=live_tts_model(settings, "ru"),
        tts_models={"en": live_tts_model(settings, "en")},
        output_format=f"pcm_{OA_RATE}",
        voice_settings=voice_settings(settings),
        timeout_s=15.0,
    )


def elevenlabs_tts(settings: Settings) -> TtsStream | None:
    provider = elevenlabs_provider(settings)
    return provider.tts_stream if provider is not None else None


async def first_message(conn: Any) -> Any:
    """Первое сообщение соединения: у websockets это recv() (его __aiter__ — асинхронный генератор, у которого
    нет __anext__), у поддельных соединений в тестах — __anext__."""
    recv = getattr(conn, "recv", None)
    if callable(recv):
        return await recv()
    return await conn.__anext__()


_WORD_END = re.compile(r"[^\w]\s*$")
_CYRILLIC = re.compile(r"[\u0400-\u04FF]")
_ENGLISH_HINT = re.compile(
    r"\b(what|where|who|when|how|why|is|are|the|can|could|you|please|hello|hi|thanks|thank|tell|show|do|does|"
    r"kurultai|quryltai|museum|toilet|restroom|stop)\b",
    re.I,
)


def foreign_gibberish(text: str) -> bool:
    """Расшифровка без единой кириллической буквы и не похожая на английский вопрос — шум, а не обращение:
    посетители говорят по-русски и по-казахски (обе письменности кириллические), иностранный гость — по-английски."""
    if not any(ch.isalpha() for ch in text) or _CYRILLIC.search(text):
        return False
    return not (text.isascii() and _ENGLISH_HINT.search(text))


_closing: set[asyncio.Task] = set()


def close_later(conn: Any) -> None:
    """Закрыть соединение с OpenAI фоном: ждать ответного закрытия незачем, а роботу это задержка."""

    async def _close() -> None:
        with contextlib.suppress(Exception):
            await asyncio.wait_for(conn.close(), 5)

    task = asyncio.ensure_future(_close())
    _closing.add(task)
    task.add_done_callback(_closing.discard)


async def probe_openai(settings: Settings, connector: Connector | None = None) -> str | None:
    """Жив ли OpenAI Realtime: подключиться и прочитать первое событие. None — отвечает (session.created или
    тишина), иначе код ошибки («credit_balance_exhausted», тип исключения). Токены не тратит.
    Для фоновой проверки предохранителя (app/kurultai/realtime/health.py)."""
    key = secret_text(settings.kurultai_openai_api_key)
    if not key:
        return "no_openai_key"
    url = f"{settings.kurultai_realtime_url}?model={settings.kurultai_realtime_model}"
    try:
        conn = await asyncio.wait_for(
            (connector or websockets.connect)(
                url, additional_headers={"Authorization": f"Bearer {key}"}, max_size=None
            ),
            settings.kurultai_realtime_connect_timeout_s,
        )
    except Exception as exc:
        return type(exc).__name__
    try:
        first = await asyncio.wait_for(
            first_message(conn), settings.kurultai_realtime_hello_timeout_s
        )
    except TimeoutError:
        return None
    except StopAsyncIteration:
        return "closed"
    except Exception as exc:
        return type(exc).__name__
    finally:
        close_later(conn)
    msg: Any = None
    if isinstance(first, str | bytes):
        with contextlib.suppress(TypeError, ValueError):
            msg = json.loads(first)
    if isinstance(msg, dict) and msg.get("type") == "error":
        return str((msg.get("error") or {}).get("code") or "error")
    return None


class RealtimeBridge:
    def __init__(
        self,
        ws: WebSocket,
        *,
        settings: Settings,
        session_id: str,
        lang: Lang,
        chat: Any,
        metrics: Any = None,
        connector: Connector | None = None,
        search: Callable[[str], Awaitable[str]] | None = None,
        tts: TtsStream | None = None,
        knowledge: Any = None,
        places: KurultaiPlaces | None = None,
        offers: OfferPolicy | None = None,
        health: Any = None,
        clock: Callable[[], float] = time.monotonic,
        noise_mode: str = "normal",
    ) -> None:
        self.ws = ws
        self.settings = settings
        # режим зала (docs/kurultai/robot-settings.md): noisy — перебивание только «стоп», server_vad грубее,
        # ответ только при человеке перед роботом (hello/state person_present; None — старое приложение: есть)
        self.noise_mode = noise_mode
        self._stop_only = (
            settings.kurultai_realtime_barge_in == "stop_only" or noise_mode == "noisy"
        )
        self._person_present: bool | None = None
        # предохранитель (app/kurultai/realtime/health.py): отказ OpenAI → сессии идут старой цепочкой
        self.health = health
        self.connect_error = ""  # почему не подключились (код OpenAI / тип исключения) — для health
        self._prefetched: list[dict] = []  # первое событие OpenAI, прочитанное в connect()
        self._handoff = ""  # Realtime бесполезен (нет денег, обрывы): отдать робота старой цепочке
        self.session_id = session_id
        self.lang: Lang = lang if lang in ("ru", "kk") else "ru"
        self.chat = chat
        self.metrics = metrics
        self._connector = connector or websockets.connect
        self._search = search or (lambda q: websearch.web_search(settings, q))
        self._text_mode = settings.kurultai_realtime_output == "text"
        # голос: общий провайдер сервера (app.state.realtime_tts); нет — свой, закрывается вместе с мостом
        self._own_tts: Any = None
        if tts is None and self._text_mode:
            self._own_tts = elevenlabs_provider(settings)
            tts = self._own_tts.tts_stream if self._own_tts is not None else None
        self._tts = tts
        self._pronunciations = settings.knowledge_dir / "pronunciations.yaml"
        self._clock = clock
        # знания Курултая через поиск (app/kurultai/rag): сводка в промпт, подробности — search_kurultai
        self.knowledge = knowledge if settings.kurultai_rag else None
        self.oa: Any = None
        self._send_lock = asyncio.Lock()
        self._audio_buf = bytearray()
        self._instructions = ""
        self._closed = False
        self._tasks: set[asyncio.Task] = set()
        # разговор
        self._engaged_until = 0.0
        self._speech_end_at: float | None = None
        self._early_item: str | None = None  # фраза, на которую ответ начат до расшифровки
        # «Ставка» вне окна разговора: ответ сочиняется до расшифровки, но фразы держатся, пока она не подтвердит
        # обращение (_held); не подтвердила — ответ отменяется, робот ничего не слышит
        self._gated = False
        self._held: list[str] = []
        self._held_done = False
        self._fallback_pending = (
            False  # OpenAI отказал до расшифровки — ответить старой цепочкой, когда придёт
        )
        self._warmup_ids: set[str] = (
            set()
        )  # ответы-прогревы (conversation: none) — их события пропускаем
        # ход робота: открыт от response.create до turn_end; робот играет звук до playback_done
        self._turn_id: str | None = None
        self._turn_open = False
        self._response_active = False
        self._response_id = ""  # текущий ответ OpenAI (response.created)
        self._cancelled: set[str] = set()
        self._partial: dict[
            str, str
        ] = {}  # расшифровка текущей фразы по кусочкам (ранний «стоп»)  # ответы, которые отменили мы: их response.done — не сбой
        self._dropping = False  # ответ отменён: остаток не озвучиваем и не шлём
        self._playing_turn: str | None = None
        self._playing_until = (
            0.0  # страховка: playback_done не пришёл — к этому времени звук доигран
        )
        self._first_audio_at: float | None = None
        self._audio_bytes = 0
        self._pcm_carry = b""  # нечётный хвост куска звука (см. _send_audio)
        self._emotion_pending = (
            False  # ответ начинается с тега эмоции [happy] — для лица робота, не для голоса
        )
        self._robot_aec = False  # у робота работает эхоподавление (hello.aec)
        self._question = ""
        self._answer = ""
        self._phrase_buf = ""
        self._phrases_in_turn = 0
        self._first_audio_ms: int | None = None
        self._stt_ms: int | None = None
        self._request_id = ""
        self._speech: asyncio.Queue | None = None  # фразы на озвучку (режим text)
        self._speaker: asyncio.Task | None = None
        self.turns = 0
        # навигация (docs/kurultai/navigation.md): точки карты и поездка
        self.places = places if places is not None else KurultaiPlaces()
        self._places_available = self.places  # после hello.places — только точки карты этого робота
        self._tools_sent: list[dict] = []
        self._pending_guide: dict | None = None  # action guide_to — уйдёт после playback_done хода
        self._navigating = False
        self._nav_place_id: str | None = None
        self._nav_auto = False  # авто-возврат на пост: сервер молчит
        self._nav_started_at: float | None = None
        self._guided = False  # в сессии уже была поездка — больше не предлагать
        # предложение проводить (OfferPolicy общий на процесс; одно на сессию)
        self.offers = offers if offers is not None else OfferPolicy()
        self._offered = False
        self._offer_turn: int | None = None  # ход с предложением: согласие — следующий ход
        self._answers = (
            0  # законченных ответов на вопросы посетителя (предложение — в конце второго)
        )
        self._turn_kind = "answer"  # answer / greeting / navigation
        self._turn_extra: str | None = (
            None  # дополнение инструкций хода (повторяется после инструмента)
        )

    # ---------- подключение ----------
    async def connect(self) -> bool:
        """Сессия OpenAI Realtime; нет ключа, голоса (режим text) или связи — False (сессия пойдёт старым pipeline)."""
        key = secret_text(self.settings.kurultai_openai_api_key)
        if not key or (self._text_mode and self._tts is None):
            return False
        url = f"{self.settings.kurultai_realtime_url}?model={self.settings.kurultai_realtime_model}"
        try:
            self.oa = await asyncio.wait_for(
                self._connector(
                    url, additional_headers={"Authorization": f"Bearer {key}"}, max_size=None
                ),
                self.settings.kurultai_realtime_connect_timeout_s,
            )
        except Exception as exc:
            self.connect_error = type(exc).__name__
            logger.warning("realtime_connect_failed", extra={"error": self.connect_error})
            return False
        # Первое событие OpenAI — session.created. Вместо него ошибка или закрытие (кончились деньги, квота,
        # ключ) → подключение не считается: сессия пойдёт старой цепочкой. Молчание — не повод отказываться.
        try:
            first = await asyncio.wait_for(
                first_message(self.oa), self.settings.kurultai_realtime_hello_timeout_s
            )
        except TimeoutError:
            return True
        except StopAsyncIteration:
            first = None
        except Exception as exc:
            first = exc
        msg: Any = None
        if isinstance(first, str | bytes):
            with contextlib.suppress(TypeError, ValueError):
                msg = json.loads(first)
        if isinstance(msg, dict) and msg.get("type") != "error":
            self._prefetched.append(msg)
            if self.health is not None:
                self.health.mark_up()
            return True
        err = (msg or {}).get("error") or {} if isinstance(msg, dict) else {}
        self.connect_error = str(
            err.get("code") or (type(first).__name__ if first is not None else "closed")
        )
        logger.warning(
            "realtime_connect_rejected",
            extra={"error": self.connect_error, "detail": str(err.get("message") or "")[:200]},
        )
        close_later(self.oa)  # не ждём рукопожатия закрытия (это стоило роботу лишних 2 с)
        self.oa = None
        return False

    async def run(self) -> None:
        """Сессия робота целиком: настройка Realtime, ready, два потока (робот → OpenAI, OpenAI → робот)."""
        try:
            await self._oa_send(self._session_config())
            if self.settings.kurultai_realtime_warmup:
                # база (~20 тыс. токенов) — в кэш OpenAI заранее, вне разговора: первый вопрос отвечается быстрее
                await self._oa_send(
                    {
                        "type": "response.create",
                        "response": {
                            "conversation": "none",
                            "input": [],
                            "output_modalities": ["text"],
                            "max_output_tokens": 16,
                            "metadata": {"purpose": "warmup"},
                        },
                    }
                )
            await self._robot_json(event("ready", audio_format=AUDIO_FORMAT))
            await self._robot_json(event("listening"))
            tasks = [
                asyncio.create_task(self._robot_loop(), name="realtime_robot"),
                asyncio.create_task(self._openai_supervisor(), name="realtime_openai"),
            ]
            done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for t in pending:
                t.cancel()
            for t in done:
                if not t.cancelled() and t.exception() is not None:
                    logger.warning(
                        "realtime_loop_failed", extra={"error": type(t.exception()).__name__}
                    )
        finally:
            await self.close()

    async def close(self, code: int = 1000, reason: str = "", error: dict | None = None) -> None:
        """Закрыть сессию: error — событие роботу перед закрытием, code/reason — код закрытия (как у пайплайна)."""
        if self._closed:
            return
        self._closed = True
        me = asyncio.current_task()
        for t in [*self._tasks, *([self._speaker] if self._speaker else [])]:
            if (
                t is not me
            ):  # закрытие из своей же задачи (_hand_off) — её не отменяем, иначе сокет не закроется
                t.cancel()
        if self.oa is not None:
            with contextlib.suppress(Exception):
                await self.oa.close()
        if self._own_tts is not None:
            with contextlib.suppress(Exception):
                await self._own_tts.aclose()
        if self.ws.client_state == WebSocketState.CONNECTED:
            with contextlib.suppress(Exception):
                if error is not None:
                    await self.ws.send_json(error)
                await self.ws.close(code, reason)

    # ---------- настройки сессии ----------
    def _now(self) -> datetime:
        return datetime.now(UTC).astimezone(ASTANA_TZ).replace(second=0, microsecond=0)

    def _build_instructions(self) -> str:
        places = self._places_available.prompt_block(self.lang)
        if self.knowledge is not None:
            return realtime_instructions_rag(
                self.knowledge.summary(), self.lang, self._now(), places
            )
        return realtime_instructions(
            self.chat.snapshot(), self.chat.materials_block(), self.lang, self._now(), places
        )

    def _tools(self) -> list[dict]:
        s = self.settings
        return (
            ([SEARCH_TOOL] if self.knowledge is not None else [])
            + ([websearch.TOOL] if s.kurultai_web_search else [])
            + ([GUIDE_TOOL] if self._places_available.targets() else [])
        )

    def _session_config(self) -> dict:
        s = self.settings
        self._instructions = self._build_instructions()
        self._tools_sent = self._tools()
        audio: dict = {
            "input": {
                "format": {"type": "audio/pcm", "rate": OA_RATE},
                "noise_reduction": {"type": "far_field"},
                "transcription": {"model": s.kurultai_realtime_transcribe_model},
                "turn_detection": self._turn_detection(),
            },
        }
        if not self._text_mode:
            audio["output"] = {
                "format": {"type": "audio/pcm", "rate": OA_RATE},
                "voice": s.kurultai_realtime_voice,
            }
        return {
            "type": "session.update",
            "session": {
                "type": "realtime",
                "instructions": self._instructions,
                "output_modalities": ["text"] if self._text_mode else ["audio"],
                "audio": audio,
                "tools": self._tools_sent,
                "tool_choice": "auto",
                **(
                    {"reasoning": {"effort": s.kurultai_realtime_reasoning}}
                    if s.kurultai_realtime_reasoning
                    else {}
                ),
            },
        }

    def _turn_detection(self) -> dict:
        """Конец реплики — у OpenAI; ответ сам не создаётся и речь его не обрывает: решает сервер."""
        st = self.settings
        if self.noise_mode == "noisy":
            # шумный зал: грубый детектор — короткие всплески и дальняя речь концом реплики не считаются
            td: dict = {
                "type": "server_vad",
                "threshold": NOISY_VAD["threshold"],
                "prefix_padding_ms": NOISY_VAD["prefix_padding_ms"],
                "silence_duration_ms": NOISY_VAD["silence_duration_ms"],
            }
        elif st.kurultai_realtime_turn_detection == "semantic_vad":
            # по смыслу: пауза «э-э…» посреди фразы не считается концом реплики
            td = {"type": "semantic_vad", "eagerness": st.kurultai_realtime_eagerness}
        else:
            td = {
                "type": "server_vad",
                "threshold": st.kurultai_realtime_vad_threshold,
                "prefix_padding_ms": 300,
                "silence_duration_ms": st.kurultai_realtime_vad_silence_ms,
            }
        return {**td, "create_response": False, "interrupt_response": False}

    async def _refresh_instructions(self) -> None:
        """Материалы, снимок, язык или минута поменялись — новые инструкции до ответа (префикс прежний — кэш)."""
        text = self._build_instructions()
        tools = self._tools()
        if text != self._instructions or tools != self._tools_sent:
            self._instructions, self._tools_sent = text, tools
            await self._oa_send(
                {
                    "type": "session.update",
                    "session": {"type": "realtime", "instructions": text, "tools": tools},
                }
            )

    async def _create_response(self, extra: str | None = None) -> None:
        """response.create хода. extra — дополнение (приветствие, предложение проводить, итог поездки): в OpenAI
        Realtime response.instructions ЗАМЕНЯЕТ инструкции сессии на этот ответ, поэтому уходят полные
        инструкции + дополнение; оно запоминается и повторяется в продолжении хода после инструмента."""
        if extra is not None:
            self._turn_extra = extra
        msg: dict = {"type": "response.create"}
        if self._turn_extra:
            msg["response"] = {"instructions": f"{self._instructions}\n\n{self._turn_extra}"}
        await self._oa_send(msg)

    # ---------- предложение проводить ----------
    def _offer(self, where: str) -> str | None:
        """Дополнение «предложи проводить в …» к этому ходу, если политика разрешает; время фиксируется сразу."""
        st = self.settings
        if not st.kurultai_offer_place or self._offered or self._guided or self._navigating:
            return None
        place = self._places_available.find(st.kurultai_offer_place)
        if place is None or not self.offers.due(st.kurultai_offer_min_interval_s):
            return None
        self._offered = True
        self._offer_turn = self.turns
        self.offers.mark()
        logger.info(
            "live_offer",
            extra={
                "place_id": place.id,
                "where": where,
                "engine": "realtime",
                "session_id": self.session_id,
            },
        )
        return offer_addition(place.name(self.lang), self.lang)

    def _answer_extra(self) -> str | None:
        """Предложение проводить — в конце второго (и далее) ответа на вопросы посетителя."""
        return self._offer("answer") if self._answers >= 1 else None

    # ---------- отправка ----------
    async def _oa_send(self, msg: dict) -> None:
        if self.oa is None or self._closed:
            return
        try:
            await self.oa.send(json.dumps(msg, ensure_ascii=False))
        except (
            Exception
        ) as exc:  # связь с OpenAI оборвалась — переподключит _openai_supervisor, робот не рвём
            if msg.get("type") != "input_audio_buffer.append":
                logger.warning(
                    "realtime_send_failed",
                    extra={"type": msg.get("type"), "error": type(exc).__name__},
                )

    async def _robot_json(self, msg: dict) -> None:
        async with self._send_lock:
            if self.ws.client_state == WebSocketState.CONNECTED:
                await self.ws.send_text(json.dumps(msg, ensure_ascii=False, separators=(",", ":")))

    async def _robot_bytes(self, data: bytes) -> None:
        async with self._send_lock:
            if self.ws.client_state == WebSocketState.CONNECTED:
                await self.ws.send_bytes(data)

    def _spawn(self, coro: Awaitable[None], name: str) -> None:
        task = asyncio.ensure_future(coro)
        task.set_name(name)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    # ---------- робот → OpenAI ----------
    async def _robot_loop(self) -> None:
        while True:
            msg = await self.ws.receive()
            if msg.get("type") == "websocket.disconnect":
                return
            data = msg.get("bytes")
            if data is not None:
                if data and len(data) % 2 == 0 and len(data) <= MAX_AUDIO_FRAME:
                    await self._audio_in(data)
                continue
            text = msg.get("text")
            if text is not None and len(text) <= MAX_TEXT_FRAME:
                try:
                    cmd = json.loads(text)
                except ValueError:
                    continue
                if isinstance(cmd, dict):
                    await self._on_robot(cmd)

    async def _audio_in(self, pcm16k: bytes) -> None:
        self._audio_buf += resample_16_to_24(pcm16k)
        if len(self._audio_buf) >= SEND_CHUNK:
            chunk, self._audio_buf = bytes(self._audio_buf), bytearray()
            await self._oa_send(
                {"type": "input_audio_buffer.append", "audio": base64.b64encode(chunk).decode()}
            )

    async def _on_robot(self, cmd: dict) -> None:
        kind = cmd.get("type")
        if kind == "hello":
            if isinstance(cmd.get("aec"), bool):
                self._robot_aec = cmd["aec"]
            if isinstance(cmd.get("person_present"), bool):
                self._person_present = cmd["person_present"]
            lang = normalize_lang(cmd.get("lang"))
            if lang in ("ru", "kk") and lang != self.lang:
                self.lang = lang
            if isinstance(cmd.get("places"), list):
                # точки карты этого робота: предлагаем и возим только туда, где точка реально есть
                self._places_available = self.places.available(cmd["places"])
            await self._refresh_instructions()
        elif kind == "visitor_arrived":
            # тап по лицу: у Курултая по умолчанию молча (KURULTAI_LIVE_GREETING); включено — поздороваться
            # (и, если политика разрешает, предложить проводить) и открыть разговор
            if getattr(self.chat, "live_greeting", False):
                self._engage()
                await self._greet()
        elif kind == "interrupt":
            if self._navigating:
                await self._stop_navigation("button")
            await self._cancel("client")
        elif kind == "playback_done":
            turn_id = cmd.get("turn_id")
            if turn_id and turn_id == self._playing_turn:
                self._playing_turn = None
                self._engage()  # окно разговора — от конца ответа робота
                await self._robot_json(event("listening"))
            if turn_id and self._pending_guide and self._pending_guide["turn_id"] == turn_id:
                await self._fire_guide()  # робот договорил «Пойдёмте, провожу» — теперь едем
        elif kind == "state":
            await self._on_state(cmd)

    # ---------- навигация ----------
    async def _greet(self) -> None:
        """Приветствие по тапу (KURULTAI_LIVE_GREETING) — ход без реплики посетителя."""
        if self._bot_busy() or self._navigating:
            return
        self._start_turn("", None, kind="greeting")
        await self._robot_json(event("thinking"))
        await self._refresh_instructions()
        offer = self._offer("greeting")
        await self._create_response(greeting_addition() + (f"\n{offer}" if offer else ""))

    def _guide_to(self, place_id: str) -> dict:
        """Инструмент guide_to: точка есть — действие откладывается до playback_done хода; нет — список точек."""
        place = self._places_available.find(place_id)
        if place is None:
            return {
                "ok": False,
                "error": "unknown place",
                "places": self._places_available.ids(),
            }
        if self._navigating:
            return {"ok": False, "error": "already navigating", "places": []}
        accepted = (
            self._offer_turn is not None
            and self._offer_turn == self.turns - 1
            and place.id == self.settings.kurultai_offer_place
        )
        self._pending_guide = {
            "place_id": place.id,
            "map_name": place.map_name,
            "turn_id": self._turn_id,
        }
        logger.info(
            "live_guide",
            extra={
                "place_id": place.id,
                "source": "offer_accepted" if accepted else "tool",
                "turn_id": self._turn_id,
                "engine": "realtime",
                "session_id": self.session_id,
            },
        )
        return {"ok": True, "place": place.name("ru")}

    async def _fire_guide(self) -> None:
        guide, self._pending_guide = self._pending_guide, None
        if guide is None or self._closed:
            return
        self._guided = True
        await self._robot_json(event("action", type_="guide_to", **guide))

    async def _guide_after(self, turn_id: str | None, delay: float) -> None:
        """playback_done не пришёл — едем через delay с после turn_end (контракт: 8 с)."""
        await asyncio.sleep(delay)
        if self._pending_guide is not None and self._pending_guide["turn_id"] == turn_id:
            await self._fire_guide()

    async def _stop_navigation(self, source: str) -> None:
        logger.info(
            "live_stop_navigation",
            extra={"source": source, "place_id": self._nav_place_id, "engine": "realtime"},
        )
        await self._robot_json(event("action", type_="stop_navigation"))

    async def _on_state(self, cmd: dict) -> None:
        """Кадр state робота: человек перед роботом (person_present), поездка началась / закончилась
        (directions и прочие поля — не наше)."""
        if isinstance(cmd.get("person_present"), bool):
            self._person_present = cmd["person_present"]
        nav = cmd.get("navigating")
        if not isinstance(nav, bool):
            return
        auto = bool(cmd.get("auto", False))
        if nav:
            self._navigating = True
            self._nav_place_id = str(cmd.get("place_id") or "") or None
            self._nav_auto = auto
            self._nav_started_at = self._clock()
            self._pending_guide = None
            if not auto:
                self._guided = True
            return
        if not self._navigating:
            return
        self._navigating = False
        place_id = str(cmd.get("place_id") or self._nav_place_id or "")
        result = str(cmd.get("result") or "unknown")
        reason = cmd.get("reason")
        started = self._nav_started_at
        auto = auto or self._nav_auto
        logger.info(
            "live_navigation_result",
            extra={
                "place_id": place_id,
                "result": result,
                "reason": str(reason) if reason else None,
                "seconds": round(self._clock() - started, 1) if started is not None else None,
                "auto": auto,
                "engine": "realtime",
                "session_id": self.session_id,
            },
        )
        if auto:
            return  # авто-возврат на пост: робот молчит
        place = self.places.find(place_id, targets_only=False)
        name = place.name(self.lang) if place is not None else place_id
        if self._bot_busy():
            await self._cancel("navigation_result")
        self._start_turn("", None, kind="navigation")
        await self._robot_json(event("thinking"))
        await self._refresh_instructions()
        await self._create_response(
            navigation_addition(name, result, str(reason) if reason else None)
        )
        self._engage()  # после поездки окно разговора открыто

    # ---------- OpenAI → робот ----------
    async def _openai_supervisor(self) -> None:
        """Связь с OpenAI оборвалась (сессия Realtime живёт ≤ 60 мин, бывают сбои сети) — переподключиться
        с паузой 1→2→5→10 с, заново настроить сессию; робот всё это время на связи. Не вышло 5 раз — конец."""
        failures = 0
        while not self._closed:
            try:
                await self._openai_loop()
                reason = "closed"
            except Exception as exc:
                reason = type(exc).__name__
                if not isinstance(exc, websockets.exceptions.ConnectionClosed):
                    logger.warning("realtime_openai_loop_error", exc_info=True)
            if self._closed:
                return
            logger.warning("realtime_openai_lost", extra={"reason": reason, "failures": failures})
            if self._turn_open or self._playing_turn is not None:
                await self._cancel("openai_lost")
            if self._handoff:
                return await self._hand_off()
            if self.health is not None and self.health.note_loss():
                return await self._hand_off("reconnect_loop")
            for delay in (1, 2, 5, 10, 10)[failures:]:
                await asyncio.sleep(delay)
                failures += 1
                if await self.connect():
                    await self._oa_send(self._session_config())
                    logger.info("realtime_openai_reconnected", extra={"attempt": failures})
                    failures = 0
                    break
            else:
                logger.warning("realtime_openai_gave_up")
                if self.health is not None:
                    self.health.mark_down(self.connect_error or "gave_up")
                return await self._hand_off("gave_up")

    async def _hand_off(self, reason: str = "") -> None:
        """Realtime сейчас бесполезен: закрыть сокет робота — приложение переподключится через 2–10 с, а новая
        сессия (health.is_down) пойдёт старой цепочкой OpenRouter + Dmitry. Ждём, пока доиграет текущий ответ."""
        reason = reason or self._handoff or "unavailable"
        for _ in range(100):  # ≤ 20 с: запасной ответ этой реплики ещё может звучать
            if not self._bot_busy():
                break
            await asyncio.sleep(0.2)
        logger.warning("realtime_handoff_pipeline", extra={"reason": reason})
        await self.close(1012, "realtime_unavailable")

    async def _openai_loop(self) -> None:
        while self._prefetched:
            await self._on_openai(self._prefetched.pop(0))
        async for raw in self.oa:
            try:
                msg = json.loads(raw)
            except (TypeError, ValueError):
                continue
            await self._on_openai(msg)

    def _is_warmup(self, msg: dict) -> bool:
        resp = msg.get("response") or {}
        if (
            msg.get("type") == "response.created"
            and (resp.get("metadata") or {}).get("purpose") == "warmup"
        ):
            self._warmup_ids.add(str(resp.get("id") or ""))
            return True
        rid = str(msg.get("response_id") or resp.get("id") or "")
        if rid and rid in self._warmup_ids:
            if msg.get("type") == "response.done":
                self._warmup_ids.discard(rid)
                usage = resp.get("usage") or {}
                logger.info(
                    "realtime_warmup",
                    extra={"input_tokens": usage.get("input_tokens"), "status": resp.get("status")},
                )
            return True
        return False

    async def _on_openai(self, msg: dict) -> None:
        kind = msg.get("type", "")
        if kind.startswith("response.") and self._is_warmup(msg):
            return
        if kind == "input_audio_buffer.speech_started":
            if not self._bot_busy() and not self._navigating:
                await self._robot_json(event("user_speaking"))
        elif kind == "input_audio_buffer.speech_stopped":
            self._speech_end_at = self._clock()
        elif kind == "input_audio_buffer.committed":
            await self._on_committed(str(msg.get("item_id") or ""))
        elif kind == "conversation.item.input_audio_transcription.delta":
            await self._on_transcript_delta(
                str(msg.get("item_id") or ""), str(msg.get("delta") or "")
            )
        elif kind == "conversation.item.input_audio_transcription.completed":
            self._partial.pop(str(msg.get("item_id") or ""), None)
            await self._on_utterance(
                str(msg.get("item_id") or ""), str(msg.get("transcript") or "")
            )
        elif kind == "conversation.item.input_audio_transcription.failed":
            item = str(msg.get("item_id") or "")
            if item and item == self._early_item:
                await self._cancel("no_transcript")
            await self._delete(item)
        elif kind == "response.created":
            self._response_active = True
            self._response_id = str((msg.get("response") or {}).get("id") or "")
        elif kind in ("response.output_audio.delta", "response.audio.delta"):
            if not self._text_mode:
                await self._on_audio(msg.get("delta") or "")
        elif kind in (
            "response.output_audio_transcript.delta",
            "response.audio_transcript.delta",
            "response.output_text.delta",
            "response.text.delta",
        ):
            await self._on_text(msg.get("delta") or "", final=False)
        elif kind in (
            "response.output_audio_transcript.done",
            "response.audio_transcript.done",
            "response.output_text.done",
            "response.text.done",
        ):
            await self._on_text("", final=True)
        elif kind == "response.done":
            await self._on_response_done(msg.get("response") or {})
        elif kind == "error":
            err = msg.get("error") or {}
            code = str(err.get("code") or "")
            if code not in ("response_cancel_not_active", "item_delete_invalid_item_id"):
                logger.warning(
                    "realtime_error", extra={"code": code, "detail": str(err.get("message"))[:200]}
                )

    # ---------- решения сервера ----------
    def _bot_busy(self) -> bool:
        if self._playing_turn is not None and self._clock() > self._playing_until:
            self._playing_turn = None  # playback_done не пришёл — звук давно доигран
        return self._turn_open or self._playing_turn is not None

    def _window(self) -> float | None:
        return getattr(self.chat, "wake_window_s", None)

    def _engaged(self) -> bool:
        return self._window() is None or self._clock() < self._engaged_until

    def _engage(self) -> None:
        window = self._window()
        if window is not None:
            self._engaged_until = self._clock() + window

    def _addressed(self, text: str) -> bool:
        if self._engaged() or self.chat.addressed(text):
            self._engage()
            return True
        return False

    def _no_person(self) -> bool:
        """Режим noisy и приложение сообщило, что перед роботом никого: реплики не отвечаем (кроме «стоп»)."""
        return self.noise_mode == "noisy" and self._person_present is False

    async def _ignore_no_person(self, item_id: str, text: str) -> None:
        await self._delete(item_id)
        if has_letters(text):
            await self._robot_json(event("user_final", text=text, lang=self.lang))
        logger.info("live_ignored_no_person", extra={"chars": len(text), "engine": "realtime"})
        await self._robot_json(event("ignored", reason="no_person"))
        await self._robot_json(event("listening"))

    async def _delete(self, item_id: str) -> None:
        if item_id:
            await self._oa_send({"type": "conversation.item.delete", "item_id": item_id})

    async def _on_committed(self, item_id: str) -> None:
        """Конец фразы. Робот молчит → ответ начинается сразу, не дожидаясь расшифровки (−0,3…0,8 с):
        в окне разговора — как обычно; вне окна — «ставка»: фразы держатся до подтверждения обращения."""
        if not item_id or self._bot_busy() or self._navigating or self._no_person():
            return
        engaged = self._engaged()
        st = self.settings
        if (engaged and not st.kurultai_realtime_early_response) or (
            not engaged and not (st.kurultai_realtime_speculative and self._text_mode)
        ):
            return
        self._early_item = item_id
        self._start_turn("", None)
        self._gated = not engaged
        if not self._gated:
            await self._robot_json(event("thinking"))
        await self._refresh_instructions()
        # предложение — только в подтверждённом окне разговора («ставка» может и не прозвучать)
        await self._create_response(None if self._gated else self._answer_extra())

    async def _on_transcript_delta(self, item_id: str, delta: str) -> None:
        """Расшифровка приходит по кусочкам: «стоп» в первых словах — замолчать сразу, не дожидаясь конца фразы
        (выигрыш — время расшифровки остатка). Слово должно быть закончено: за ним пробел или знак («Стойка…»)."""
        if not item_id or not delta or self._navigating or self._gated or not self._bot_busy():
            return
        text = self._partial.get(item_id, "") + delta
        self._partial = {item_id: text}
        if _WORD_END.search(text) and stop_request(text)[0]:
            logger.info("realtime_stop_early", extra={"chars": len(text)})
            await self._cancel("stop")

    async def _on_utterance(self, item_id: str, text: str) -> None:
        text = text.strip()
        if self._navigating:
            # в поездке не отвечаем: только «стоп / тоқта / хватит» останавливает робота
            if item_id and item_id == self._early_item:
                self._early_item = None
                await self._cancel("navigating")
            await self._delete(item_id)
            if not has_letters(text):
                return
            await self._robot_json(event("user_final", text=text, lang=self.lang))
            if is_stop_command(text):
                await self._stop_navigation("voice")
            else:
                logger.info(
                    "live_ignored_navigating", extra={"chars": len(text), "engine": "realtime"}
                )
                await self._robot_json(event("ignored", reason="navigating"))
            await self._robot_json(event("listening"))
            return
        if item_id and item_id == self._early_item:  # ответ уже сочиняется — решаем по расшифровке
            self._early_item = None
            gated = self._gated
            stop, rest = stop_request(
                text
            )  # «стоп» без вопроса — не реплика; «стоп, а где…» — вопрос
            speech = has_letters(text) and not (stop and not rest) and not self._is_noise(text)
            if speech and self._no_person():  # человек ушёл, пока фраза расшифровывалась
                await self._cancel("no_person")
                await self._ignore_no_person(item_id, text)
                return
            addressed = speech and (not gated or self.chat.addressed(text))
            if not addressed:
                await self._cancel("not_addressed" if gated else ("stop" if text else "noise"))
                await self._delete(item_id)
                if gated and has_letters(text):
                    await self._robot_json(event("user_final", text=text, lang=self.lang))
                    logger.info(
                        "live_not_addressed", extra={"chars": len(text), "engine": "realtime"}
                    )
                    await self._robot_json(event("ignored", reason="not_addressed"))
                return
            self._question = text
            self._stt_ms = self._since_speech_end()
            await self._robot_json(event("user_final", text=text, lang=self.lang))
            self._engage()
            if gated:
                await self._release()
            elif self._fallback_pending:
                self._fallback_pending = False
                self._spawn(self._fallback_answer(), "realtime_fallback")
            return
        if not has_letters(text):
            await self._delete(item_id)
            return
        if self._bot_busy() and self._gated:
            # «ставка» на предыдущую фразу ещё не подтверждена — это не перебивание: ставку снять (робот ничего
            # не говорил), а эту фразу решать как обычно, с проверкой обращения
            await self._cancel("not_addressed")
        if self._bot_busy():
            # робот говорит: «стоп» — замолчать; осмысленная фраза (не эхо его голоса) — перебить и ответить на
            # неё (KURULTAI_REALTIME_BARGE_IN=speech); эхо и обрывки в разговор не идут
            stop, rest = stop_request(text)
            if stop:
                if not rest:
                    await self._delete(item_id)  # команда в разговор не идёт
                await self._cancel("stop")
                if not rest:
                    return
                # «Стоп, а где туалет?» — замолчал и сразу отвечает на остальное
                logger.info("realtime_stop_then_question", extra={"chars": len(text)})
                self._start_turn(text, self._since_speech_end())
                await self._robot_json(event("user_final", text=text, lang=self.lang))
                await self._robot_json(event("thinking"))
                await self._refresh_instructions()
                await self._create_response(self._answer_extra())
                return
            if self._stop_only or not self._real_speech(text):
                await self._delete(item_id)
                return
            await self._cancel("barge_in")
            logger.info("realtime_barge_in", extra={"chars": len(text)})
            self._start_turn(text, self._since_speech_end())
            await self._robot_json(event("user_final", text=text, lang=self.lang))
            await self._robot_json(event("thinking"))
            await self._refresh_instructions()
            await self._create_response(self._answer_extra())
            return
        if self._no_person() and not is_stop_command(text):
            await self._ignore_no_person(item_id, text)
            return
        stt_ms = self._since_speech_end()
        await self._robot_json(event("user_final", text=text, lang=self.lang))
        stop, rest = stop_request(
            text
        )  # робот уже замолчал (по первым словам) или молчал: «стоп» — не вопрос
        if (stop and not rest) or not self._addressed(text):
            await self._delete(item_id)
            logger.info("live_not_addressed", extra={"chars": len(text), "engine": "realtime"})
            await self._robot_json(event("ignored", reason="not_addressed"))
            await self._robot_json(event("listening"))
            return
        self._start_turn(text, stt_ms)
        await self._robot_json(event("thinking"))
        await self._refresh_instructions()
        await self._create_response(self._answer_extra())

    @staticmethod
    def _is_noise(text: str) -> bool:
        """Не реплика: обрывок в одно слово (не из коротких осмысленных — «да», «привет», «рахмет»…) или «чужой
        язык» — распознавание приняло шум либо невнятную речь за литовский, ирландский, армянский и т. п.
        (10.11.2026: «Akutė išskašai pirmše», «Agus briseadh», «Թիլմ է» — робот отвечал на них заготовками)."""
        if foreign_gibberish(text):
            return True
        words = [w.lower() for w in _WORDS.findall(text)]
        return len(words) <= 1 and not (words and words[0] in SHORT_OK)

    def _real_speech(self, text: str) -> bool:
        """Фраза посетителя, а не эхо голоса робота: хотя бы 2 слова, и не больше половины из них — слова
        ответа, который робот сейчас говорит (микрофон слышит динамик)."""
        words = [w.lower() for w in _WORDS.findall(text)]
        if len(words) < 2:
            return False
        spoken = {w.lower() for w in _WORDS.findall(self._answer)}
        echo = sum(1 for w in words if w in spoken)
        return echo * 2 <= len(words)

    def _since_speech_end(self) -> int | None:
        if self._speech_end_at is None:
            return None
        return max(0, int((self._clock() - self._speech_end_at) * 1000))

    def _start_turn(self, question: str, stt_ms: int | None, kind: str = "answer") -> None:
        self._turn_id = new_turn_id()
        self._request_id = new_request_id()
        self._turn_open = True
        self._turn_kind = kind
        self._turn_extra = None
        self._dropping = False
        self._question, self._answer, self._phrase_buf = question, "", ""
        self._phrases_in_turn = 0
        self._gated = False
        self._held, self._held_done = [], False
        self._fallback_pending = False
        self._first_audio_ms = None
        self._first_audio_at = None
        self._audio_bytes = 0
        self._pcm_carry = b""
        self._emotion_pending = True
        self._stt_ms = stt_ms
        self.turns += 1
        if self._text_mode:
            self._speech = asyncio.Queue()
            self._speaker = asyncio.ensure_future(self._speak_loop(self._turn_id, self._speech))

    # ---------- звук ответа ----------
    async def _send_audio(self, pcm: bytes) -> None:
        # Звук роботу — только целыми отсчётами PCM16 (чётное число байт): ElevenLabs по HTTP отдаёт куски
        # произвольной длины, а AudioTrack робота на нечётном куске падает (write < 0) и дальше молчит.
        if self._dropping or not pcm:
            return
        pcm = self._pcm_carry + pcm
        if len(pcm) % 2:
            self._pcm_carry, pcm = pcm[-1:], pcm[:-1]
        else:
            self._pcm_carry = b""
        if not pcm:
            return
        self._audio_bytes += len(pcm)
        if self._first_audio_ms is None:
            self._first_audio_at = self._clock()
            self._first_audio_ms = self._since_speech_end()
            await self._robot_json(event("turn_start", turn_id=self._turn_id))
        await self._robot_bytes(pcm)

    async def _on_audio(self, b64: str) -> None:
        """Режим voice: звук модели как есть."""
        if self._dropping or not self._turn_open or not b64:
            return
        await self._send_audio(base64.b64decode(b64))

    async def _on_text(self, delta: str, final: bool) -> None:
        """Текст ответа (режим text) или расшифровка звука (режим voice) → фразы: субтитры и озвучка."""
        if self._dropping or not self._turn_open:
            return
        self._phrase_buf += delta
        if self._emotion_pending:
            buf = self._phrase_buf.lstrip()
            if not final and (
                not buf or (buf.startswith("[") and "]" not in buf and len(buf) < 16)
            ):
                return  # пока только пробелы, или тег эмоции ещё приходит по кусочкам
            self._emotion_pending = False
            m = _EMOTION.match(self._phrase_buf)
            if m:
                self._phrase_buf = self._phrase_buf[m.end() :]
                name = m.group(1).lower()
                if name in EMOTIONS:
                    await self._robot_json(event("emotion", name=name))
        while True:
            m = _SENTENCE.match(self._phrase_buf)
            if self._phrases_in_turn == 0 and self._text_mode:
                # первая фраза — до запятой, если она раньше точки: Dmitry начинает говорить раньше
                c = _CLAUSE.match(self._phrase_buf)
                if c and (not m or c.end(1) < m.end(1)):
                    m = c
            if not m:
                break
            phrase = m.group(1).strip()
            self._phrase_buf = self._phrase_buf[m.end() :]
            if phrase:
                await self._phrase(phrase)
        if final and self._phrase_buf.strip():
            await self._phrase(self._phrase_buf.strip())
            self._phrase_buf = ""

    async def _phrase(self, phrase: str) -> None:
        self._phrases_in_turn += 1
        self._answer = f"{self._answer} {phrase}".strip()
        if self._gated:
            self._held.append(phrase)  # «ставка»: прозвучит после подтверждения обращения
            return
        if self._text_mode and self._speech is not None:
            self._speech.put_nowait(phrase)  # субтитр уйдёт вместе с её звуком
        else:
            await self._robot_json(event("phrase", text=phrase))

    async def _release(self) -> None:
        """Обращение подтверждено: «ставка» становится обычным ответом — держанные фразы в озвучку."""
        self._gated = False
        await self._robot_json(event("thinking"))
        if self._fallback_pending:
            self._fallback_pending = False
            self._spawn(self._fallback_answer(), "realtime_fallback")
            return
        held, self._held = self._held, []
        for phrase in held:
            if self._speech is not None:
                self._speech.put_nowait(phrase)
        if self._held_done and self._speech is not None:
            self._speech.put_nowait(None)

    async def _fallback_answer(self) -> None:
        """OpenAI отказал (лимит, сбой) — ответ старой цепочкой: агент Курултая (OpenRouter), голос Dmitry."""
        from app.schemas import ChatRequest

        turn_id = self._turn_id
        logger.warning("realtime_fallback_pipeline", extra={"question_chars": len(self._question)})
        req = ChatRequest(question=self._question, lang=self.lang, session_id=self.session_id)
        try:
            async for kind, data in self.chat.chat_stream(
                req, self._request_id or new_request_id()
            ):
                if self._turn_id != turn_id or not self._turn_open:
                    return
                if kind == "phrase" and data.get("text"):
                    await self._phrase(str(data["text"]))
        except Exception as exc:
            logger.warning("realtime_fallback_failed", extra={"error": type(exc).__name__})
        if self._turn_id == turn_id and self._turn_open and self._speech is not None:
            self._speech.put_nowait(None)

    async def _speak_loop(self, turn_id: str | None, queue: asyncio.Queue) -> None:
        """Режим text: фразы по очереди голосом Dmitry потоком; None в очереди — ответ закончен."""
        assert self._tts is not None
        while True:
            phrase = await queue.get()
            if phrase is None or self._dropping or self._turn_id != turn_id:
                break
            await self._robot_json(event("phrase", text=phrase))
            spoken = normalize_for_tts(phrase, self.lang, self._pronunciations)
            try:
                async for chunk in self._tts(spoken, self.lang):
                    if self._dropping or self._turn_id != turn_id:
                        return
                    await self._send_audio(chunk)
            except Exception as exc:
                logger.warning("realtime_tts_failed", extra={"error": type(exc).__name__})
        if self._turn_id == turn_id and self._turn_open and not self._dropping:
            await self._end_turn(ok=True)

    async def _on_response_done(self, response: dict) -> None:
        rid = str(response.get("id") or "")
        if rid and rid in self._cancelled:
            # ответ, который мы отменили (response.cancel): новый ход мог уже начаться — его не трогаем
            self._cancelled.discard(rid)
            if not self._turn_open:
                self._dropping = False
            return
        if not rid or rid == self._response_id:
            self._response_active = False
        if self._dropping or not self._turn_open:
            self._dropping = False
            return
        if response.get("status") == "cancelled":
            # отменён не по нашему response.cancel (interrupt_response выключен — так быть не должно):
            # не сбой, старой цепочкой не отвечаем — ход просто заканчивается
            if self._text_mode and self._speech is not None:
                await self._on_text("", final=True)
                self._speech.put_nowait(None)
            else:
                await self._end_turn(ok=False)
            return
        calls = [o for o in response.get("output") or [] if o.get("type") == "function_call"]
        if calls and response.get("status") == "completed":
            await self._on_text("", final=True)  # «Сейчас посмотрю» — озвучить до поиска
            self._spawn(self._run_tools(calls), "realtime_tools")
            return
        ok = response.get("status") in (None, "completed")
        if not ok:
            details = response.get("status_details") or {}
            code = str((details.get("error") or {}).get("code") or details.get("reason") or "")
            logger.warning(
                "realtime_response_failed", extra={"status": response.get("status"), "code": code}
            )
            if code in FATAL_CODES and not self._handoff:
                # нет денег / квоты / ключ: эту реплику ответит старая цепочка, дальше — робота ей и отдаём
                self._handoff = code
                if self.health is not None:
                    self.health.mark_down(code)
                self._spawn(self._hand_off(code), "realtime_handoff")
        if self._text_mode and self._speech is not None:
            await self._on_text("", final=True)
            if not ok and self._phrases_in_turn == 0:
                if self._turn_kind != "answer":
                    self._speech.put_nowait(
                        None
                    )  # приветствие / итог поездки не вышли — ход молча кончается
                    return
                # OpenAI не ответил (лимит токенов в минуту и т. п.) — эту реплику отвечает старая цепочка
                if self._gated or not self._question:
                    self._fallback_pending = True  # ждём расшифровку (и подтверждение обращения)
                else:
                    self._spawn(self._fallback_answer(), "realtime_fallback")
                return
            if self._gated:
                self._held_done = True  # конец хода — после подтверждения обращения (_release)
                return
            self._speech.put_nowait(None)  # конец хода — после последней фразы (_speak_loop)
            return
        await self._end_turn(ok=ok)

    async def _run_tools(self, calls: list[dict]) -> None:
        for call in calls:
            name, call_id = call.get("name"), call.get("call_id")
            try:
                args = json.loads(call.get("arguments") or "{}")
            except ValueError:
                args = {}
            if name == "search_kurultai" and self.knowledge is not None:
                started = self._clock()
                query = str(args.get("query") or "")
                try:
                    result = await self.knowledge.search(query, self.settings.kurultai_rag_results)
                except Exception as exc:  # БД недоступна — модель скажет, что данных нет
                    logger.warning(
                        "kurultai_rag_search_failed", extra={"error": type(exc).__name__}
                    )
                    result = "Поиск по данным Курултая недоступен."
                logger.info(
                    "realtime_search_kurultai", extra={"ms": int((self._clock() - started) * 1000)}
                )
            elif name == "web_search":
                started = self._clock()
                result = await self._search(str(args.get("query") or ""))
                logger.info(
                    "realtime_web_search", extra={"ms": int((self._clock() - started) * 1000)}
                )
            elif name == "guide_to":
                result = self._guide_to(str(args.get("place_id") or ""))
            else:
                result = "Неизвестный инструмент."
            if not self._turn_open:  # пока искали, ответ отменили («стоп», сон)
                return
            output = result if isinstance(result, dict) else {"result": result}
            await self._oa_send(
                {
                    "type": "conversation.item.create",
                    "item": {
                        "type": "function_call_output",
                        "call_id": call_id,
                        "output": json.dumps(output, ensure_ascii=False),
                    },
                }
            )
        if self._turn_open:
            # продолжение после инструмента — новый ответ модели, он тоже начинается с тега эмоции
            self._emotion_pending = True
            await self._create_response()

    async def _end_turn(self, ok: bool) -> None:
        turn_id = self._turn_id
        if not self._text_mode:
            await self._on_text("", final=True)
        self._turn_open = False
        if self._first_audio_ms is None and self._first_audio_at is None:
            await self._robot_json(
                event("listening")
            )  # звука не было — робот не ждёт playback_done
        else:
            self._playing_turn = turn_id
            started = self._first_audio_at if self._first_audio_at is not None else self._clock()
            self._playing_until = started + self._audio_bytes / (OA_RATE * 2) + 3.0
            await self._robot_json(event("turn_end", turn_id=turn_id, lang=self.lang, found=True))
        if self._turn_kind == "answer" and self._phrases_in_turn > 0:
            self._answers += 1
        if self._pending_guide is not None and self._pending_guide["turn_id"] == turn_id:
            if self._first_audio_at is None:
                await self._fire_guide()  # сказать было нечего — едем сразу
            else:
                self._spawn(
                    self._guide_after(turn_id, self.settings.kurultai_guide_playback_timeout_s),
                    "realtime_guide_timer",
                )
        logger.info(
            "live_answered",
            extra={
                "engine": "realtime",
                "output": "text+elevenlabs" if self._text_mode else "openai_voice",
                "kind": self._turn_kind,
                "question": self._question[:500],
                "answer": self._answer[:1000],
                "lang": self.lang,
                "first_audio_ms": self._first_audio_ms,
                "profile": "kurultai",
            },
        )
        self._record(ok=ok and self._first_audio_at is not None)

    async def _cancel(self, reason: str) -> None:
        """«Стоп» / кнопка / сон / шум: ответ отменить, остаток не слать, робот сбрасывает воспроизведение."""
        if not self._turn_open and self._playing_turn is None:
            return
        turn_id = self._turn_id
        self._dropping = True
        if self._response_active:
            if self._response_id:
                self._cancelled.add(self._response_id)
            await self._oa_send({"type": "response.cancel"})
        else:
            self._dropping = self._turn_open  # ответ уже сгенерирован: глушим только озвучку
        if self._speech is not None:
            self._speech.put_nowait(None)
        was_open = self._turn_open
        had_audio = self._first_audio_at is not None
        self._turn_open = False
        self._playing_turn = None
        self._pending_guide = None  # «Пойдёмте, провожу» перебили — не едем
        self._early_item = None
        self._gated, self._held, self._fallback_pending = False, [], False
        if had_audio or reason in ("stop", "client"):
            await self._robot_json(event("interrupted", turn_id=turn_id))
        await self._robot_json(event("listening"))
        if reason in ("not_addressed", "noise") or (reason == "no_person" and not had_audio):
            return  # «ставка» не сыграла — робот ничего не говорил
        logger.info("realtime_interrupted", extra={"reason": reason})
        if was_open:
            self._record(ok=True, interrupted=True)

    def _record(self, ok: bool, interrupted: bool = False) -> None:
        if self.metrics is None:
            return
        with contextlib.suppress(Exception):
            self.metrics.record(
                "live",
                {"live_stt_final_ms": self._stt_ms, "live_first_audio_ms": self._first_audio_ms},
                lang=self.lang,
                provider="openai_realtime",
                request_id=self._request_id,
                session_id=self.session_id,
                ok=ok,
                extra={
                    "engine": "realtime",
                    "output": "text+elevenlabs" if self._text_mode else "openai_voice",
                    "profile": "kurultai",
                    "interrupted": interrupted,
                },
            )
