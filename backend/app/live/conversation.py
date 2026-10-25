"""Ход разговора WS /v1/live: конец реплики, перебивание, ответ агента, события для робота.

Стоит в пайплайне после STT и перед TTS (app/live/session.py):

    transport.input → EchoGate → VAD (Silero) → STT (по отрезкам: STT_PROVIDER; или ElevenLabs realtime)
        → LiveConversation
        → TTS (ElevenLabs) → transport.output → PlayoutTracker

Реплика посетителя. VAD сообщает начало/паузу речи (VADUserStarted/StoppedSpeakingFrame), STT — промежуточный
и финальный текст (Interim/TranscriptionFrame; финал — на каждую паузу VAD, ручной commit). На паузе VAD
smart-turn v3 решает, договорил ли посетитель (STT по отрезкам, LIVE_STT_MODE=segmented: промежуточного
текста нет, на каждую паузу VAD — один финал отрезка; конец реплики ждёт текст всех отрезков, перебивание
решается по тексту отрезка): да — конец реплики сразу; нет — ждём тишину до
LIVE_TURN_MAX_SILENCE_S (без smart-turn — LIVE_TURN_SILENCE_S). Затем user_final и ход агента.

Ответ. Тот же агент, что у /v1/chat (ChatService: промпт, тулы, память, эмоции, фильтры, шум → ignored без
вызова модели), но фразы приходят по мере генерации (LiveTurn). Явный запрос места («где кафе?», «проводите
до лифта», «да» на «Проводить вас?») ChatService отвечает шаблоном без модели (app/agent/fastpath.py).
Каждая фраза — событие phrase и TTSSpeakFrame; события хода идут обычными кадрами и встают в очередь за звуком
(TTS придерживает не-системные кадры за своими аудио-контекстами), поэтому порядок у клиента: turn_start →
emotion → phrase → звук → … → turn_end. guide_to придерживается до playback_done этого хода; where_is и look
уходят сразу. Все action несут turn_id своего хода.

Голос. Язык выбирается по каждой фразе ответа (app/lang.py tts_lang): казахский текст — всегда голос kk и
eleven_v3. Текст для синтеза нормализуется (app/tts_text.py: многоточия, скобки, междометия, числа словами,
произношение), длинная фраза режется по запятым (kk — мельче, первая — особенно), субтитр остаётся целым.
Звук шаблонных фраз — из кеша (app/live/tts_cache.py).

Язык реплики — по тексту и языку от STT (app/lang.py utterance_lang); язык не из ru/kk/en — язык сессии.

Перебивание. Речь посетителя, пока ход робота не закончен (модель думает или звук ещё играет), — кандидат.
Перебиванием он становится, когда речь длится ≥ LIVE_BARGE_IN_MIN_MS (без AEC — LIVE_ECHO_MIN_MS) и
промежуточный текст — не шум (app/noise.py) и не эхо собственной фразы робота. Тогда: отмена агента,
InterruptionFrame вниз (TTS бросает синтез, транспорт — очередь звука), событие interrupted, в память —
только прозвучавшее (по часам воспроизведения Playout, последняя фраза обрезается по словам). Кандидат,
не дотянувший до порога («угу» во время ответа), отбрасывается целиком.

Тексты реплик в логи и метрики не попадают; latency_events — только миллисекунды и метки.
"""

from __future__ import annotations

import asyncio
import contextlib
import re
import time
from collections.abc import Awaitable, Callable
from contextlib import aclosing
from dataclasses import dataclass, field
from pathlib import Path

from pipecat.audio.turn.base_turn_analyzer import BaseTurnAnalyzer, EndOfTurnState
from pipecat.frames.frames import (
    CancelFrame,
    EndFrame,
    Frame,
    InputAudioRawFrame,
    InputTransportMessageFrame,
    InterimTranscriptionFrame,
    InterruptionFrame,
    OutputAudioRawFrame,
    OutputTransportMessageFrame,
    OutputTransportMessageUrgentFrame,
    StartFrame,
    TranscriptionFrame,
    TTSSpeakFrame,
    VADUserStartedSpeakingFrame,
    VADUserStoppedSpeakingFrame,
)
from pipecat.processors.frame_processor import FrameDirection, FrameProcessor

from app.agent.service import ChatService, LiveTurn
from app.config import Settings
from app.lang import Lang, normalize_lang, stt_lang_code, tts_lang, utterance_lang
from app.live.playout import EchoGate, Playout
from app.live.protocol import AUDIO_OUT_RATE, error_event, event, new_turn_id
from app.live.tts_cache import PhraseAudioCache
from app.logging_setup import logger, new_request_id
from app.metrics import LatencyRecorder
from app.noise import is_noise
from app.schemas import ChatRequest
from app.text import split_phrases
from app.tts_text import is_filler_only, normalize_for_tts

_WORD = re.compile(r"[^\W_]+", re.UNICODE)
MAX_QUESTION_CHARS = 500


def _words(text: str) -> list[str]:
    return [w.lower() for w in _WORD.findall(text)]


def is_echo(heard: str, spoken: list[str]) -> bool:
    """Распознанный текст — это эхо фраз робота (≥ 70 % слов из того, что робот сейчас говорит)."""
    words = _words(heard)
    if not words:
        return False
    vocab = {w for phrase in spoken for w in _words(phrase)}
    return sum(w in vocab for w in words) / len(words) >= 0.7


_CLAUSE = re.compile(r"(?<=[,;:])\s+|\s+(?=[—–]\s)")


def tts_chunks(text: str, max_chars: int, min_chars: int = 12) -> list[str]:
    """Фраза для синтеза — кусками не длиннее max_chars по запятым / «—» (короче кусок — раньше звук).
    Без удобного места разреза фраза остаётся целой; кусок короче min_chars прилипает к соседнему."""
    if max_chars <= 0 or len(text) <= max_chars:
        return [text]
    parts = [p.strip() for p in _CLAUSE.split(text) if p.strip()]
    out: list[str] = []
    for part in parts:
        if out and (len(out[-1]) < min_chars or len(out[-1]) + 1 + len(part) <= max_chars):
            out[-1] = f"{out[-1]} {part}"
        else:
            out.append(part)
    if len(out) > 1 and len(out[-1]) < min_chars:
        tail = out.pop()
        out[-1] = f"{out[-1]} {tail}"
    return out


def cut_words(phrase: str, fraction: float) -> str:
    """Начало фразы, которое успело прозвучать (по доле времени), с многоточием."""
    words = phrase.split()
    keep = max(1, round(len(words) * fraction))
    return phrase if keep >= len(words) else " ".join(words[:keep]) + "…"


@dataclass
class _BotTurn:
    turn_id: str
    request_id: str
    lang: Lang
    live: LiveTurn | None  # None — ход без агента (описание кадра /look)
    eot_at: float | None = None  # конец реплики посетителя (perf_counter), для метрик
    speech_end_at: float | None = None
    task: asyncio.Task | None = None
    phrases: list[str] = field(default_factory=list)
    guide: list[dict] = field(default_factory=list)  # guide_to — после playback_done
    started: bool = False  # turn_start отправлен
    generating: bool = True
    ended: bool = False  # turn_end отправлен (в очередь за звуком)
    finished: bool = False  # ход закончен: доиграл, перебит или ошибка
    interrupted: bool = False
    found: bool = True
    stt_final_ms: int | None = None
    first_audio_ms: int | None = None
    llm_first_token_ms: int | None = None
    finish_task: asyncio.Task | None = None


@dataclass
class LiveStats:
    turns: int = 0
    interrupts: int = 0
    ignored: int = 0
    errors: int = 0


class LiveConversation(FrameProcessor):
    def __init__(
        self,
        *,
        chat: ChatService,
        settings: Settings,
        session_id: str,
        lang: Lang,
        playout: Playout,
        echo_gate: EchoGate,
        metrics: LatencyRecorder,
        turn_analyzer: BaseTurnAnalyzer | None = None,
        switch_tts: Callable[[Lang], Frame | None] | None = None,
        set_stt_lang: Callable[[Lang], Awaitable[None]] | None = None,
        provider: str = "pipecat",
        stt_segmented: bool = False,
        phrase_cache: PhraseAudioCache | None = None,
        warmup: Callable[[Lang], Awaitable[None]] | None = None,
        pronunciations: Path | None = None,
        tts_text: Callable[[str, Lang], str] | None = None,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        self.chat = chat
        self.settings = settings
        self.session_id = session_id
        self.lang: Lang = lang
        self.place_id: str | None = None
        self.navigating = False
        self.playout = playout
        self.echo_gate = echo_gate
        self.metrics = metrics
        self.turn_analyzer = turn_analyzer
        self.switch_tts = switch_tts
        self.set_stt_lang = set_stt_lang
        self.provider = provider
        # STT по отрезкам (LIVE_STT_MODE=segmented): текст — один финал на каждую паузу VAD, без промежуточного
        self.stt_segmented = stt_segmented
        self.phrase_cache = phrase_cache
        self.warmup = warmup
        self.pronunciations = pronunciations
        self.tts_text = tts_text  # тег подачи v3 и т. п. — только в запрос синтеза
        self.stats = LiveStats()
        self._tts_lang: Lang | None = None

        # реплика посетителя
        self._vad_speaking = False
        self._speech_start: float | None = None  # monotonic, настоящее начало (минус start_secs)
        self._speech_end: float | None = None
        self._user_open = False
        self._finals: list[str] = []
        self._stt_lang: Lang | None = None  # язык, который вернул STT для этой реплики (ru/kk/en)
        self._partial = ""  # промежуточный текст текущего сегмента (ещё без финала)
        self._final_at: float | None = None  # perf_counter последнего финала
        self._final_event = asyncio.Event()
        self._segments_pending = 0  # segmented: отрезки ушли в STT, финал ещё не пришёл
        self._eot_task: asyncio.Task | None = None
        # кандидат в перебивание (речь во время ответа робота)
        self._barge_start: float | None = None
        self._barge_timer: asyncio.Task | None = None
        # ход робота
        self._turn: _BotTurn | None = None
        self._last_turn: _BotTurn | None = None  # для playback_done после конца хода
        self._tasks: set[asyncio.Task] = set()
        self._closed = False

        playout.on_first_audio = self._on_first_audio
        playout.on_turn_end = self._on_turn_end

    # ---------- служебное ----------
    def _spawn(self, coro, name: str) -> asyncio.Task:
        task = self.create_task(coro, name)
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return task

    async def _cancel(self, task: asyncio.Task | None) -> None:
        if task is not None and not task.done():
            await self.cancel_task(task)

    async def send_now(self, message: dict) -> None:
        """Событие сразу (мимо очереди звука): состояние посетителя, interrupted, ошибки."""
        await self.push_frame(OutputTransportMessageUrgentFrame(message=message))

    async def _send_queued(self, message: dict) -> None:
        """Событие хода — в очередь за звуком, в порядке озвучки."""
        await self.push_frame(OutputTransportMessageFrame(message=message))

    def bot_active(self) -> bool:
        return self._turn is not None and not self._turn.finished

    def _barge_min_s(self) -> float:
        ms = self.settings.live_barge_in_min_ms
        if not self.echo_gate.aec:
            ms = max(ms, self.settings.live_echo_min_ms)
        return ms / 1000

    # ---------- кадры ----------
    async def process_frame(self, frame: Frame, direction: FrameDirection) -> None:
        await super().process_frame(frame, direction)
        if isinstance(frame, InputAudioRawFrame):
            if self.turn_analyzer is not None:
                self.turn_analyzer.append_audio(frame.audio, self._vad_speaking)
            return  # звук посетителя дальше STT не нужен
        if isinstance(frame, StartFrame):
            await self.push_frame(frame, direction)
            return
        if isinstance(frame, EndFrame | CancelFrame):
            await self.close(interrupted=True)
            await self.push_frame(frame, direction)
            return
        if direction != FrameDirection.DOWNSTREAM:
            await self.push_frame(frame, direction)
            return
        if isinstance(frame, VADUserStartedSpeakingFrame):
            await self._on_vad_start(frame.start_secs)
        elif isinstance(frame, VADUserStoppedSpeakingFrame):
            await self._on_vad_stop(frame.stop_secs)
        elif isinstance(frame, InterimTranscriptionFrame):
            await self._on_partial(frame.text)
        elif isinstance(frame, TranscriptionFrame):
            await self._on_final(frame.text, stt_lang_code(getattr(frame, "language", None)))
        elif isinstance(frame, InputTransportMessageFrame):
            await self._on_client(frame.message)
        else:
            await self.push_frame(frame, direction)

    # ---------- речь посетителя ----------
    async def _on_vad_start(self, start_secs: float) -> None:
        self._vad_speaking = True
        now = time.monotonic()
        turn = self._turn
        if (
            turn is not None
            and turn.ended
            and not turn.finished
            and not self.playout.playing(tail=0.1)
        ):
            await self._finish(
                turn, interrupted=False
            )  # звук уже доиграл, таймер конца хода не успел
        if self.bot_active():
            if self._barge_start is None:
                self._barge_start = now - start_secs
                self._finals, self._partial = [], ""
                wait = self._barge_start + self._barge_min_s() - now
                self._barge_timer = self._spawn(self._barge_after(max(0.0, wait)), "live_barge")
            return
        self._speech_start = now - start_secs
        await self._cancel(self._eot_task)  # посетитель продолжил после паузы — та же реплика
        self._eot_task = None
        if not self._user_open:
            self._user_open = True
            self._finals, self._partial, self._final_at = [], "", None
            self._stt_lang = None
            await self.send_now(event("user_speaking"))

    async def _on_vad_stop(self, stop_secs: float) -> None:
        self._vad_speaking = False
        self._speech_end = time.monotonic() - stop_secs
        if self.stt_segmented:
            self._segments_pending += 1  # STT выше по пайплайну уже поставил отрезок в очередь
        if self._barge_start is not None:
            long_enough = self._speech_end - self._barge_start >= self._barge_min_s()
            if self.stt_segmented and long_enough and self.settings.live_barge_in_require_text:
                # текст отрезка придёт после паузы — перебивание решаем по нему (_on_final), не дольше таймаута
                await self._cancel(self._barge_timer)
                self._barge_timer = self._spawn(
                    self._drop_barge_after(self.settings.live_stt_segment_timeout_s), "live_barge"
                )
                return
            await self._drop_barge()  # кандидат не дотянул до перебивания: «угу» во время ответа
            return
        if self._user_open:
            await self._cancel(self._eot_task)
            self._eot_task = self._spawn(self._end_of_turn(self._speech_end), "live_eot")

    async def _drop_barge(self) -> None:
        self._barge_start = None
        await self._cancel(self._barge_timer)
        self._barge_timer = None
        self._finals, self._partial = [], ""
        if self.turn_analyzer is not None:
            self.turn_analyzer.clear()

    async def _drop_barge_after(self, delay: float) -> None:
        await asyncio.sleep(delay)
        if self._barge_start is not None and not self._vad_speaking:
            self._barge_timer = None  # это мы и есть — не отменять себя
            await self._drop_barge()

    def _heard_text(self) -> str:
        return " ".join([*self._finals, self._partial]).strip()

    async def _on_partial(self, text: str) -> None:
        self._partial = text.strip()
        if self._barge_start is not None:
            await self._maybe_barge()
        elif self._user_open:
            await self.send_now(event("user_partial", text=self._heard_text()))

    async def _on_final(self, text: str, lang: Lang | None = None) -> None:
        text = text.strip()
        self._partial = ""
        if self.stt_segmented:
            self._segments_pending = max(0, self._segments_pending - 1)
        if text:
            self._finals.append(text)
            self._final_at = time.perf_counter()
            if lang is not None:
                self._stt_lang = lang
        self._final_event.set()
        if self._barge_start is not None:
            await self._maybe_barge()
            if self.stt_segmented and self._barge_start is not None and not self._vad_speaking:
                await self._drop_barge()  # текст отрезка — шум, эхо или пусто: не перебивание
        elif self._user_open and text:
            await self.send_now(event("user_partial", text=self._heard_text()))

    async def _barge_after(self, delay: float) -> None:
        await asyncio.sleep(delay)
        await self._maybe_barge()

    async def _maybe_barge(self) -> None:
        start = self._barge_start
        if start is None or not self.bot_active():
            return
        if time.monotonic() - start < self._barge_min_s():
            return
        if self.settings.live_barge_in_require_text:
            heard = self._heard_text()
            turn = self._turn
            if not heard or is_noise(heard) or (turn is not None and is_echo(heard, turn.phrases)):
                return
        self._barge_start = None
        await self._cancel(self._barge_timer)
        self._barge_timer = None
        await self.interrupt("speech", start)
        # перебившая речь — начало новой реплики посетителя
        self._speech_start = start
        self._user_open = True
        await self.send_now(event("user_speaking"))
        if self._heard_text():
            await self.send_now(event("user_partial", text=self._heard_text()))
        if not self._vad_speaking and self._speech_end is not None:
            self._eot_task = self._spawn(self._end_of_turn(self._speech_end), "live_eot")

    async def _end_of_turn(self, speech_end: float) -> None:
        """Пауза VAD → договорил ли посетитель (smart-turn / тишина) → финальный текст → ход агента."""
        complete = False
        if self.turn_analyzer is not None:
            try:
                state, _ = await self.turn_analyzer.analyze_end_of_turn()
                complete = state == EndOfTurnState.COMPLETE
            except Exception as exc:
                logger.warning("live_smart_turn_failed", extra={"error": type(exc).__name__})
            silence = self.settings.live_turn_max_silence_s
        else:
            silence = self.settings.live_turn_silence_s
        if not complete:
            await asyncio.sleep(max(0.0, speech_end + silence - time.monotonic()))
            if self.turn_analyzer is not None:
                self.turn_analyzer.clear()
        if self.stt_segmented:
            # текст каждого отрезка приходит целиком после паузы: ждём все отрезки этой реплики
            deadline = speech_end + self.settings.live_stt_segment_timeout_s
        else:
            # финальный текст последнего сегмента: ждём, пока есть промежуточный без финала
            deadline = speech_end + self.settings.live_stt_final_timeout_s
            if not self._finals and not self._partial:
                deadline = min(deadline, time.monotonic() + 0.5)  # могла быть речь без текста

        def waiting() -> bool:
            if self.stt_segmented:
                return self._segments_pending > 0
            return bool(self._partial or not self._finals)

        while waiting() and time.monotonic() < deadline:
            self._final_event.clear()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._final_event.wait(), deadline - time.monotonic())
        text = self._heard_text()
        stt_lang = self._stt_lang
        stt_final_ms = (
            max(0, int((self._final_at - _perf_at(speech_end)) * 1000))
            if self._final_at is not None
            else None
        )
        self._user_open = False
        self._finals, self._partial, self._final_at = [], "", None
        self._stt_lang = None
        self._eot_task = None
        if not text:
            await self.send_now(event("listening"))
            return
        await self._complete_turn(text, speech_end, stt_final_ms, stt_lang)

    async def _complete_turn(
        self,
        text: str,
        speech_end: float,
        stt_final_ms: int | None,
        stt_lang: Lang | None = None,
    ) -> None:
        text = text[:MAX_QUESTION_CHARS]
        # язык реплики: по тексту и языку от STT; STT вернул не ru/kk/en или ничего — язык сессии
        lang = utterance_lang(text, stt_lang, self.lang)
        await self.send_now(event("user_final", text=text, lang=lang))
        if not is_noise(text):
            await self.send_now(event("thinking"))
            if lang != self._tts_lang:
                self.warm(lang)  # другой язык, чем звучал: голос мог остыть
        req = ChatRequest(
            question=text, lang=lang, session_id=self.session_id, place_id=self.place_id
        )
        turn = self._new_turn(lang, live=LiveTurn())
        turn.eot_at = time.perf_counter()
        turn.speech_end_at = _perf_at(speech_end)
        turn.task = self._spawn(self._run_agent(turn, req, stt_final_ms), "live_agent")

    def _new_turn(self, lang: Lang, live: LiveTurn | None) -> _BotTurn:
        turn = _BotTurn(turn_id=new_turn_id(), request_id=new_request_id(), lang=lang, live=live)
        self._turn = self._last_turn = turn
        self.stats.turns += 1
        return turn

    # ---------- ответ ----------
    def warm(self, lang: Lang) -> None:
        """Прогреть голос языка в фоне (сам прогрев не чаще раза в интервал на процесс)."""
        if self.warmup is not None and not self._closed:
            self._spawn(self.warmup(lang), "live_tts_warmup")

    async def _start_turn(self, turn: _BotTurn) -> None:
        if turn.started:
            return
        turn.started = True
        await self._send_queued(event("turn_start", turn_id=turn.turn_id))

    async def _use_voice(self, lang: Lang) -> None:
        """Голос и модель TTS под язык фразы (kk — eleven_v3, ru/en — flash)."""
        if self.switch_tts is None or lang == self._tts_lang:
            return
        frame = self.switch_tts(lang)
        if frame is not None:
            await self.push_frame(frame)
        self._tts_lang = lang

    def _chunk_limit(self, lang: Lang, first: bool) -> int:
        s = self.settings
        if lang == "kk":
            return s.live_tts_first_chunk_chars_kk if first else s.live_tts_chunk_chars_kk
        return s.live_tts_chunk_chars

    async def _speak(self, turn: _BotTurn, phrase: str, cache: bool = False) -> None:
        if is_filler_only(phrase):  # «Хм…» отдельной фразой — ни звука, ни субтитра
            return
        first = not turn.phrases
        turn.phrases.append(phrase)
        lang = tts_lang(phrase, turn.lang)
        spoken = normalize_for_tts(phrase, lang, self.pronunciations)
        await self._use_voice(lang)
        await self._send_queued(event("phrase", text=phrase))
        store = self.phrase_cache if cache and self.settings.live_tts_phrase_cache else None
        audio = store.get(spoken, lang) if store is not None else None
        if audio is not None:  # шаблонная фраза уже звучала — звук сразу, без синтеза
            await self.push_frame(
                OutputAudioRawFrame(audio=audio, sample_rate=AUDIO_OUT_RATE, num_channels=1)
            )
            return
        for chunk in tts_chunks(spoken, self._chunk_limit(lang, first)):
            text = self.tts_text(chunk, lang) if self.tts_text is not None else chunk
            await self.push_frame(TTSSpeakFrame(text=text))
        if store is not None:
            store.fill_later(spoken, lang)

    async def _run_agent(self, turn: _BotTurn, req: ChatRequest, stt_final_ms: int | None) -> None:
        error: str | None = None
        ignored = False
        try:
            async with aclosing(self.chat.chat_stream(req, turn.request_id, live=turn.live)) as evs:
                async for kind, data in evs:
                    if turn.finished:
                        return
                    if kind == "done":
                        if data.get("ignored"):
                            ignored = True
                            break
                        turn.lang, turn.found = data["lang"], data["found"]
                    elif kind == "error":
                        error = data["error"]["code"]
                        await self.send_now(data | {"type": "error"})
                    elif kind == "emotion":
                        await self._start_turn(turn)
                        await self._send_queued(event("emotion", name=data["name"]))
                    elif kind == "phrase":
                        await self._start_turn(turn)
                        await self._speak(turn, data["text"], cache=bool(data.get("cache")))
                    elif kind == "action":
                        await self._start_turn(turn)
                        if data["type"] == "guide_to":
                            turn.guide.append(data)
                        else:
                            await self._send_queued(_action_event(data, turn.turn_id))
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("live_agent_failed")
            error = "internal_error"
            await self.send_now(error_event("internal_error", "Internal server error"))
        finally:
            turn.generating = False
            if turn.live is not None and turn.live.first_token_at is not None and turn.eot_at:
                turn.llm_first_token_ms = int((turn.live.first_token_at - turn.eot_at) * 1000)
        turn.stt_final_ms = stt_final_ms
        if ignored:
            self.stats.ignored += 1
            turn.finished = True
            self._turn = None
            await self.send_now(event("ignored"))
            await self.send_now(event("listening"))
            self._record_turn(turn, ignored=True)
            return
        self.stats.errors += error is not None
        if not turn.started:  # ничего не прозвучало (ошибка до первой фразы)
            await self._finish(turn, interrupted=False)
            self._record_turn(turn, error=error)
            return
        turn.ended = True  # журнал, память и метрики — когда доиграет (_finish)
        await self._send_queued(
            event("turn_end", turn_id=turn.turn_id, lang=turn.lang, found=turn.found)
        )

    async def speak_text(self, text: str, lang: Lang | None = None) -> bool:
        """Ход без агента (описание кадра от /v1/look): озвучить готовый ответ, если робот свободен."""
        if self._user_open or self._closed:
            return False
        current = self._turn
        if current is not None and not current.finished:
            if current.generating:
                return False
            await self._finish(
                current, interrupted=False
            )  # «Сейчас посмотрю» ещё звучит — встанем за ним
        turn = self._new_turn(lang or self.lang, live=None)
        turn.generating = False
        await self._start_turn(turn)
        await self._send_queued(event("emotion", name="neutral"))
        for phrase in split_phrases(text):
            await self._speak(turn, phrase)
        turn.ended = True
        await self._send_queued(event("turn_end", turn_id=turn.turn_id, lang=turn.lang, found=True))
        return True

    async def greet(self) -> None:
        if self.bot_active() or self._user_open:
            return
        req = ChatRequest(
            question="",
            lang=self.lang,
            session_id=self.session_id,
            place_id=self.place_id,
            event="visitor_arrived",
        )
        turn = self._new_turn(self.lang, live=LiveTurn())
        turn.task = self._spawn(self._run_agent(turn, req, None), "live_greeting")

    # ---------- воспроизведение и конец хода ----------
    def _on_first_audio(self, turn_id: str, at: float) -> None:
        turn = self._turn
        if turn is None or turn.turn_id != turn_id or turn.speech_end_at is None:
            return
        turn.first_audio_ms = max(0, int((_perf_at(at) - turn.speech_end_at) * 1000))

    def _on_turn_end(self, turn_id: str, clock_end: float) -> None:
        turn = self._turn
        if turn is None or turn.turn_id != turn_id or turn.finished:
            return
        delay = max(0.0, clock_end - time.monotonic()) + 0.05
        turn.finish_task = self._spawn(self._finish_after(turn, delay), "live_finish")

    async def _finish_after(self, turn: _BotTurn, delay: float) -> None:
        await asyncio.sleep(delay)
        if not turn.finished:
            await self._finish(turn, interrupted=False)

    async def _finish(self, turn: _BotTurn, interrupted: bool) -> None:
        """Ход закончен: журнал и память (только прозвучавшее), робот снова слушает."""
        if turn.finished:
            return
        turn.finished = True
        turn.interrupted = interrupted
        if self._turn is turn:
            self._turn = None
        if interrupted:
            heard = self.playout.heard() if self.playout.turn_id == turn.turn_id else {}
            said = []
            for i, phrase in enumerate(turn.phrases):
                frac = heard.get(i, 0.0)
                if frac >= 0.98:
                    said.append(phrase)
                elif frac > 0:
                    said.append(cut_words(phrase, frac))
        else:
            said = list(turn.phrases)
        if turn.live is not None:
            try:
                await turn.live.commit(said, interrupted)
            except Exception as exc:
                logger.warning("live_commit_failed", extra={"error": type(exc).__name__})
        if turn.ended and turn.live is not None:
            self._record_turn(turn)
        if not interrupted and not self._closed:
            await self.send_now(event("listening"))

    async def interrupt(self, reason: str, started: float | None = None) -> bool:
        """Остановить ход робота: агент, синтез, звук у клиента (interrupted)."""
        turn = self._turn
        if turn is None or turn.finished:
            return False
        self.stats.interrupts += 1
        if (
            turn.task is not None
            and not turn.task.done()
            and turn.task is not asyncio.current_task()
        ):
            turn.task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await turn.task  # chat_stream успевает передать журнал/память в LiveTurn
        await self._cancel(turn.finish_task)
        await self.push_frame(InterruptionFrame())
        await self.send_now(event("interrupted", turn_id=turn.turn_id))
        now = time.monotonic()
        await self._finish(turn, interrupted=True)
        self.playout.flush(now)
        if started is not None:
            self.metrics.record(
                "live",
                {"live_interrupt_ms": int((now - started) * 1000)},
                lang=turn.lang,
                provider=self.provider,
                request_id=turn.request_id,
                session_id=self.session_id,
                extra={"reason": reason, "aec": self.echo_gate.aec},
            )
        logger.info(
            "live_interrupted",
            extra={"session_id": self.session_id, "reason": reason, "turn_id": turn.turn_id},
        )
        return True

    # ---------- команды робота ----------
    async def _on_client(self, msg: dict) -> None:
        kind = msg.get("type")
        if kind == "hello":
            lang = normalize_lang(msg.get("lang"))
            if lang and lang != self.lang:
                self.lang = lang
                if self.set_stt_lang is not None and self.settings.live_stt_language_hint:
                    await self.set_stt_lang(lang)
            self._set_place(msg.get("place_id"))
            if isinstance(msg.get("aec"), bool):
                self.echo_gate.aec = msg["aec"]
        elif kind == "visitor_arrived":
            await self.greet()
        elif kind == "interrupt":
            await self.interrupt("client")
            await self.send_now(event("listening"))
        elif kind == "playback_done":
            await self._on_playback_done(str(msg.get("turn_id") or ""))
        elif kind == "state":
            self._set_place(msg.get("place_id"))
            if isinstance(msg.get("navigating"), bool):
                self.navigating = msg["navigating"]

    def _set_place(self, place_id: object) -> None:
        if isinstance(place_id, str) and 0 < len(place_id) <= 64:
            self.place_id = place_id

    async def _on_playback_done(self, turn_id: str) -> None:
        turn = self._last_turn
        if turn is None or turn.turn_id != turn_id or turn.interrupted:
            return
        if turn.ended and not turn.finished:
            await self._cancel(turn.finish_task)
            self.playout.flush()
            await self._finish(turn, interrupted=False)
        guide, turn.guide = turn.guide, []
        for action in guide:  # провожать — только после того, как робот договорил
            await self.send_now(_action_event(action, turn.turn_id))

    # ---------- метрики ----------
    def _record_turn(
        self,
        turn: _BotTurn,
        *,
        ignored: bool = False,
        error: str | None = None,
    ) -> None:
        self.metrics.record(
            "live",
            {
                "live_stt_final_ms": turn.stt_final_ms,
                "live_llm_first_token_ms": turn.llm_first_token_ms,
                "live_first_audio_ms": turn.first_audio_ms,
                **({"live_ignored": 0} if ignored else {}),
                **({"live_error": None} if error else {}),
            },
            lang=turn.lang,
            provider=self.provider,
            request_id=turn.request_id,
            session_id=self.session_id,
            ok=error is None,
            extra={
                "greeting": turn.speech_end_at is None and turn.live is not None,
                "interrupted": turn.interrupted,
                "phrases": len(turn.phrases),
                "aec": self.echo_gate.aec,
                "ignored": ignored,
                "error": error,
            },
            keep_null=("live_error",),
        )

    # ---------- закрытие ----------
    async def close(self, interrupted: bool) -> None:
        """Сессия кончилась: недоигранный ход — в память как перебитый, фоновые задачи — отменить."""
        if self._closed:
            return
        self._closed = True
        turn = self._turn
        if turn is not None and not turn.finished:
            if turn.task is not None and not turn.task.done():
                turn.task.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await turn.task
            await self._finish(turn, interrupted=interrupted)
        for task in list(self._tasks):
            await self._cancel(task)


def _action_event(action: dict, turn_id: str) -> dict:
    """action хода: turn_id — по нему робот связывает guide_to с playback_done этого хода."""
    return event("action", type_=action["type"], place_id=action.get("place_id"), turn_id=turn_id)


def _perf_at(monotonic_t: float) -> float:
    """Отметка time.monotonic() → шкала time.perf_counter() (для разниц с метками агента)."""
    return time.perf_counter() - (time.monotonic() - monotonic_t)
