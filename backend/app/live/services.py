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
- voice_settings — те же, что у /v1/tts (ELEVENLABS_STABILITY и т. д.; для v3 — только stability).

Фабрика подменяется в тестах (create_app(live_services=...)): фейковые VAD/STT/TTS без сети.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import aiohttp
from pipecat.audio.turn.base_turn_analyzer import BaseTurnAnalyzer
from pipecat.audio.vad.vad_analyzer import VADAnalyzer, VADParams
from pipecat.frames.frames import Frame, ManuallySwitchServiceFrame, STTUpdateSettingsFrame
from pipecat.pipeline.service_switcher import ServiceSwitcher, ServiceSwitcherStrategyManual
from pipecat.processors.frame_processor import FrameProcessor
from pipecat.transcriptions.language import Language

from app.config import Settings
from app.lang import LANGS, Lang
from app.live.protocol import AUDIO_IN_RATE, AUDIO_OUT_RATE
from app.speech.elevenlabs import VoiceSettings


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


LiveServicesFactory = Callable[[Settings, Lang], LiveServices]


def vad_params(settings: Settings) -> VADParams:
    return VADParams(
        confidence=settings.live_vad_confidence,
        start_secs=settings.live_vad_start_s,
        stop_secs=settings.live_vad_stop_s,
        min_volume=settings.live_vad_min_volume,
    )


def voice_settings(settings: Settings) -> VoiceSettings:
    return VoiceSettings(
        stability=settings.elevenlabs_stability,
        similarity_boost=settings.elevenlabs_similarity,
        style=settings.elevenlabs_style,
        use_speaker_boost=settings.elevenlabs_speaker_boost,
        speed=settings.elevenlabs_speed,
    )


def build_live_services(settings: Settings, lang: Lang) -> LiveServices:
    """Настоящие сервисы: Silero + smart-turn + ElevenLabs (нужен ELEVENLABS_API_KEY)."""
    from pipecat.audio.turn.smart_turn.base_smart_turn import SmartTurnParams
    from pipecat.audio.turn.smart_turn.local_smart_turn_v3 import LocalSmartTurnAnalyzerV3
    from pipecat.audio.vad.silero import SileroVADAnalyzer
    from pipecat.services.elevenlabs.stt import ElevenLabsRealtimeSTTService
    from pipecat.services.elevenlabs.tts import ElevenLabsHttpTTSService, ElevenLabsTTSService

    assert settings.elevenlabs_api_key is not None
    key = settings.elevenlabs_api_key.get_secret_value()
    hint = settings.live_stt_language_hint
    stt = ElevenLabsRealtimeSTTService(
        api_key=key,
        sample_rate=AUDIO_IN_RATE,
        settings=ElevenLabsRealtimeSTTService.Settings(
            model=settings.elevenlabs_realtime_model,
            language=Language(lang) if hint else None,
        ),
    )
    voice = voice_settings(settings)
    voices = {
        "ru": settings.elevenlabs_voice_ru,
        "kk": settings.elevenlabs_voice_kk,
        "en": settings.elevenlabs_voice_en,
    }
    http = aiohttp.ClientSession()
    ws_url = settings.elevenlabs_base_url.replace("https://", "wss://").replace("http://", "ws://")
    tts_by_lang: dict[str, FrameProcessor] = {}
    for code in LANGS:
        model = settings.elevenlabs_tts_model_kk if code == "kk" else settings.live_tts_model
        fields = voice.for_model(model)
        if model.startswith("eleven_v3"):  # WebSocket ElevenLabs не принимает v3 — HTTP-поток
            tts_by_lang[code] = ElevenLabsHttpTTSService(
                api_key=key,
                aiohttp_session=http,
                base_url=settings.elevenlabs_base_url,
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
        await stt.queue_frame(
            STTUpdateSettingsFrame(delta=ElevenLabsRealtimeSTTService.Settings(language=code))
        )

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
        provider=f"pipecat:elevenlabs:{settings.live_tts_model}",
        aclose=http.close,
    )
