"""Сервисы Pipecat для WS /v1/live (сверено с исходниками pipecat-ai 1.14.0 и docs.pipecat.ai, 2026-10):

- VAD — `SileroVADAnalyzer` (ONNX-модель в пакете pipecat), в пайплайне через `VADProcessor`.
- Конец реплики — `LocalSmartTurnAnalyzerV3` (smart-turn v3.2, ONNX ~9 МБ в пакете, CPU, ~10–50 мс).
- STT — `ElevenLabsRealtimeSTTService` (scribe_v2_realtime, PCM 16 кГц, ручной commit на паузе VAD —
  финальный текст сразу после паузы, не ждём VAD ElevenLabs).
- TTS — ElevenLabs, вывод PCM 24 кГц (`pcm_24000`, в `ready.audio_format` — `pcm_s16le_24000`):
  ru/en — `ElevenLabsTTSService` (WebSocket multi-stream-input, текст отдаётся по мере генерации,
  минимальная задержка; модель eleven_flash_v2_5); kk — `ElevenLabsHttpTTSService` (HTTP-поток, eleven_v3:
  казахский есть только в v3, а WebSocket ElevenLabs модели v3 не принимает — elevenlabs.io/docs,
  «Text to Speech vs Text to Dialogue WebSockets»). Переключение — `ServiceSwitcher` по языку хода.
- voice_settings — те же, что у /v1/tts (ELEVENLABS_STABILITY и т. д.; для v3 — ELEVENLABS_V3_STABILITY).
  Голоса — ELEVENLABS_VOICE_RU/KK/EN, модели — LIVE_TTS_MODEL[_RU/_EN/_KK] (kk — только v3/v4).
- Голос выбирается по языку каждой фразы ответа (LiveConversation, app/lang.py tts_lang).
- synth_pcm — фраза целиком в PCM 24 кГц по REST ElevenLabs: прогрев голосов при открытии сессии
  (LIVE_TTS_WARMUP) и звук шаблонных фраз быстрого пути в кеш TTS.
- STT по отрезкам: тишина (пик ниже STT_SILENCE_PEAK) и обрывки короче 0,25 с в STT не уходят.

Фабрика подменяется в тестах (create_app(live_services=...)): фейковые VAD/STT/TTS без сети.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import AsyncGenerator, Awaitable, Callable
from dataclasses import dataclass

import aiohttp
import numpy as np
from pipecat.audio.turn.base_turn_analyzer import BaseTurnAnalyzer
from pipecat.audio.turn.smart_turn.base_smart_turn import SmartTurnParams
from pipecat.audio.turn.smart_turn.local_smart_turn_v3 import LocalSmartTurnAnalyzerV3
from pipecat.audio.vad.silero import SileroVADAnalyzer
from pipecat.audio.vad.vad_analyzer import VADAnalyzer, VADParams
from pipecat.frames.frames import (
    Frame,
    ManuallySwitchServiceFrame,
    STTUpdateSettingsFrame,
    TranscriptionFrame,
)
from pipecat.pipeline.service_switcher import ServiceSwitcher, ServiceSwitcherStrategyManual
from pipecat.processors.frame_processor import FrameProcessor
from pipecat.services.elevenlabs.stt import ElevenLabsRealtimeSTTService
from pipecat.services.elevenlabs.tts import ElevenLabsHttpTTSService, ElevenLabsTTSService
from pipecat.services.settings import STTSettings
from pipecat.services.stt_service import SegmentedSTTService
from pipecat.transcriptions.language import Language
from pipecat.utils.time import time_now_iso8601

from app.config import Settings
from app.lang import LANGS, Lang, stt_lang_code
from app.live.protocol import AUDIO_IN_RATE, AUDIO_OUT_RATE
from app.logging_setup import logger
from app.speech.base import SpeechError, SttProvider
from app.speech.elevenlabs import VoiceSettings, kk_safe_model
from app.speech.factory import build_stt_provider

# Прогрев голоса: короткое слово на языке голоса
WARMUP_TEXT: dict[str, str] = {"ru": "Привет", "kk": "Сәлем", "en": "Hi"}
# (voice_id, model) → time.monotonic() последнего прогрева в процессе
_WARMED: dict[tuple[str, str], float] = {}
# Модели, которые принимает WebSocket ElevenLabs (multi-stream-input); остальные (v3, v4) — HTTP-поток
_WS_MODEL_PREFIXES = ("eleven_flash", "eleven_turbo", "eleven_multilingual")
# Отрезок речи короче — обрывок, в STT не уходит
MIN_SEGMENT_S = 0.25


@dataclass
class LiveServices:
    vad: VADAnalyzer
    stt: FrameProcessor
    tts: FrameProcessor
    turn_analyzer: BaseTurnAnalyzer | None = None
    switch_tts: Callable[[Lang], Frame | None] | None = (
        None  # кадр переключения голоса под язык хода
    )
    set_stt_lang: Callable[[Lang], Awaitable[None]] | None = None
    provider: str = "pipecat"
    aclose: Callable[[], Awaitable[None]] | None = None
    # STT по отрезкам: финальный текст приходит после паузы VAD, промежуточного нет
    stt_segmented: bool = False
    # Фраза целиком → PCM 24 кГц тем же голосом и моделью, что в live (кеш шаблонных фраз); None — без кеша
    synth_pcm: Callable[[str, Lang], Awaitable[bytes]] | None = None
    # Голос + модель + voice_settings языка — в ключ кеша звука
    tts_signature: Callable[[Lang], str] | None = None
    # Прогреть голос языка (не чаще LIVE_TTS_WARMUP_INTERVAL_S на процесс); None — без прогрева
    warmup: Callable[[Lang], Awaitable[None]] | None = None
    # Текст фразы → текст запроса синтеза для языка (v3: тег подачи «[cheerfully]»); None — как есть
    tts_text: Callable[[str, Lang], str] | None = None


class SegmentedProviderSTT(SegmentedSTTService):
    """Отрезок речи (от начала до паузы VAD) целиком → наш SttProvider (OpenRouter / Gemini) → один финал.

    Звук копится только пока VAD слышит речь, поэтому тишина и простой микрофона ничего не стоят.
    На каждый отрезок — ровно один TranscriptionFrame (пустой, если речи не разобрали или STT упал):
    LiveConversation считает отрезки и ждёт текст каждого.
    """

    def __init__(
        self,
        provider: SttProvider,
        lang: Lang | None,
        *,
        sample_rate: int,
        timeout_s: float,
        silence_peak: int = 0,
        min_speech_s: float = MIN_SEGMENT_S,
    ) -> None:
        super().__init__(
            sample_rate=sample_rate,
            ttfs_p99_latency=timeout_s,
            settings=STTSettings(model=getattr(provider, "name", None), language=None),
        )
        self._provider = provider
        self.lang = lang
        self._timeout_s = timeout_s
        self._silence_peak = silence_peak
        self._min_bytes = int(min_speech_s * sample_rate) * 2
        self.skipped = 0  # отрезков-тишины, не ушедших в STT

    def _is_silence(self, audio: bytes) -> bool:
        if len(audio) < self._min_bytes:
            return True
        if self._silence_peak <= 0 or len(audio) < 2:
            return False
        samples = np.frombuffer(audio[: len(audio) // 2 * 2], dtype=np.int16)
        return int(np.abs(samples.astype(np.int32)).max()) < self._silence_peak

    @property
    def wants_wav_segments(self) -> bool:
        return False  # SttProvider.stt принимает PCM и сам заворачивает в WAV

    def can_generate_metrics(self) -> bool:
        return False

    async def run_stt(self, audio: bytes) -> AsyncGenerator[Frame, None]:
        text, lang = "", None
        if self._is_silence(audio):  # тишина или щелчок: провайдер не вызываем, отрезок — пустой
            self.skipped += 1
        else:
            try:
                res = await asyncio.wait_for(
                    self._provider.stt(audio, self.sample_rate, self.lang), self._timeout_s
                )
                text, lang = res.text.strip(), res.lang
                detected = getattr(res, "detected", None)
                if detected is not None and stt_lang_code(detected) is None:
                    lang = None  # STT услышал другой язык — решит язык сессии
            except (SpeechError, TimeoutError) as exc:
                logger.warning("live_segment_stt_failed", extra={"error": type(exc).__name__})
        yield TranscriptionFrame(
            text, "", time_now_iso8601(), language=Language(lang) if lang else None
        )


LiveServicesFactory = Callable[[Settings, Lang], LiveServices]


def vad_params(settings: Settings) -> VADParams:
    return VADParams(
        confidence=settings.live_vad_confidence,
        start_secs=settings.live_vad_start_s,
        stop_secs=settings.live_vad_stop_s,
        min_volume=settings.live_vad_min_volume,
    )


def voice_settings(settings: Settings) -> VoiceSettings:
    return VoiceSettings.from_settings(settings)


def live_tts_model(settings: Settings, code: Lang) -> str:
    """Модель live для языка: LIVE_TTS_MODEL_<LANG>, иначе ru/en — LIVE_TTS_MODEL, kk — ELEVENLABS_TTS_MODEL_KK.
    Для kk — только модели с казахским (flash отвечает unsupported_language)."""
    if code == "kk":
        model = settings.live_tts_model_kk or settings.elevenlabs_tts_model_kk
        safe = kk_safe_model(model)
        if safe != model:
            logger.warning("live_tts_model_kk_unsupported", extra={"model": model, "used": safe})
        return safe
    return getattr(settings, f"live_tts_model_{code}") or settings.live_tts_model


def live_voices(settings: Settings) -> dict[str, str]:
    return {
        "ru": settings.elevenlabs_voice_ru,
        "kk": settings.elevenlabs_voice_kk,
        "en": settings.elevenlabs_voice_en,
    }


def make_warmup(
    synth: Callable[[str, Lang], Awaitable[bytes]],
    voices: dict[str, str],
    models: dict[str, str],
    interval_s: float,
    clock: Callable[[], float] = time.monotonic,
) -> Callable[[Lang], Awaitable[None]]:
    """Прогрев голоса: короткий синтез в никуда, не чаще interval_s на голос и модель в процессе."""

    async def warmup(code: Lang) -> None:
        key = (voices[code], models[code])
        now = clock()
        last = _WARMED.get(key)
        if last is not None and now - last < interval_s:
            return
        _WARMED[key] = now  # сразу: параллельные сессии не греют тот же голос дважды
        started = time.perf_counter()
        try:
            await synth(WARMUP_TEXT[code], code)
        except asyncio.CancelledError:
            if _WARMED.get(key) == now:  # сессия закрылась раньше: голос не прогрет
                del _WARMED[key]
            raise
        except Exception as exc:  # прогрев не должен мешать разговору
            logger.warning("live_tts_warmup_failed", extra={"lang": code, "error": str(exc)[:200]})
            return
        logger.info(
            "live_tts_warmup",
            extra={
                "lang": code,
                "model": models[code],
                "ms": int((time.perf_counter() - started) * 1000),
            },
        )

    return warmup


def build_live_services(settings: Settings, lang: Lang) -> LiveServices:
    """Настоящие сервисы: Silero + smart-turn + ElevenLabs (нужен ELEVENLABS_API_KEY)."""

    assert settings.elevenlabs_api_key is not None
    key = settings.elevenlabs_api_key.get_secret_value()
    hint = settings.live_stt_language_hint
    segmented = settings.live_stt_mode == "segmented"
    stt_provider: SttProvider | None = None
    stt: FrameProcessor
    if segmented:
        stt_provider = build_stt_provider(settings)
        stt = SegmentedProviderSTT(
            stt_provider,
            lang if hint else None,
            sample_rate=AUDIO_IN_RATE,
            timeout_s=settings.live_stt_segment_timeout_s,
            silence_peak=settings.stt_silence_peak,
        )
    else:
        stt = ElevenLabsRealtimeSTTService(
            api_key=key,
            sample_rate=AUDIO_IN_RATE,
            settings=ElevenLabsRealtimeSTTService.Settings(
                model=settings.elevenlabs_realtime_model,
                language=Language(lang) if hint else None,
            ),
        )
    voice = voice_settings(settings)
    voices = live_voices(settings)
    models = {code: live_tts_model(settings, code) for code in LANGS}
    http = aiohttp.ClientSession()
    base_url = settings.elevenlabs_base_url.rstrip("/")
    ws_url = base_url.replace("https://", "wss://").replace("http://", "ws://")
    tts_by_lang: dict[str, FrameProcessor] = {}
    for code in LANGS:
        model = models[code]
        fields = voice.for_model(model)
        if not model.startswith(
            _WS_MODEL_PREFIXES
        ):  # WebSocket ElevenLabs не принимает v3 — HTTP-поток
            tts_by_lang[code] = ElevenLabsHttpTTSService(
                api_key=key,
                aiohttp_session=http,
                base_url=base_url,
                sample_rate=AUDIO_OUT_RATE,
                settings=ElevenLabsHttpTTSService.Settings(
                    voice=voices[code], model=model, language=Language(code), **fields
                ),
            )
        else:
            tts_by_lang[code] = ElevenLabsTTSService(
                api_key=key,
                url=ws_url,
                sample_rate=AUDIO_OUT_RATE,
                settings=ElevenLabsTTSService.Settings(
                    voice=voices[code], model=model, language=Language(code), **fields
                ),
            )
    order = [lang, *(c for c in LANGS if c != lang)]  # первый — активный с начала
    switcher = ServiceSwitcher(
        services=[tts_by_lang[c] for c in order], strategy_type=ServiceSwitcherStrategyManual
    )

    def switch_tts(code: Lang) -> Frame:
        return ManuallySwitchServiceFrame(service=tts_by_lang[code])

    async def set_stt_lang(code: Lang) -> None:
        if isinstance(stt, SegmentedProviderSTT):
            stt.lang = code
            return
        await stt.queue_frame(
            STTUpdateSettingsFrame(delta=ElevenLabsRealtimeSTTService.Settings(language=code))
        )

    async def synth_pcm(text: str, code: Lang) -> bytes:
        """REST ElevenLabs, PCM 24 кГц — тот же голос, модель и voice_settings, что у live."""
        model = models[code]
        body = {
            "text": voice.tag_text(model, text),
            "model_id": model,
            "voice_settings": voice.for_model(model),
            "language_code": code,
        }
        async with http.post(
            f"{base_url}/v1/text-to-speech/{voices[code]}",
            params={"output_format": f"pcm_{AUDIO_OUT_RATE}"},
            json=body,
            headers={"xi-api-key": key},
            timeout=aiohttp.ClientTimeout(total=30),
        ) as r:
            if r.status != 200:
                detail = (await r.text())[:200]
                raise SpeechError(f"elevenlabs tts http {r.status}: {detail}")
            return await r.read()

    def tts_text(text: str, code: Lang) -> str:
        return voice.tag_text(models[code], text)

    def tts_signature(code: Lang) -> str:
        model = models[code]
        return f"{voices[code]}:{model}:pcm_{AUDIO_OUT_RATE}:{voice.signature(model)}"

    async def aclose() -> None:
        await http.close()
        client = getattr(stt_provider, "client", None)
        if client is not None and hasattr(client, "aclose"):
            await client.aclose()

    turn_analyzer = None
    if settings.live_smart_turn:
        turn_analyzer = LocalSmartTurnAnalyzerV3(
            sample_rate=AUDIO_IN_RATE,
            params=SmartTurnParams(stop_secs=settings.live_turn_max_silence_s),
        )
    return LiveServices(
        vad=SileroVADAnalyzer(sample_rate=AUDIO_IN_RATE, params=vad_params(settings)),
        stt=stt,
        tts=switcher,
        turn_analyzer=turn_analyzer,
        switch_tts=switch_tts,
        set_stt_lang=set_stt_lang,
        provider=f"pipecat:elevenlabs:{models['ru']}"
        + (f"+stt:{stt_provider.name}" if stt_provider is not None else ""),
        aclose=aclose,
        stt_segmented=segmented,
        synth_pcm=synth_pcm,
        tts_signature=tts_signature,
        warmup=make_warmup(synth_pcm, voices, models, settings.live_tts_warmup_interval_s),
        tts_text=tts_text,
    )
