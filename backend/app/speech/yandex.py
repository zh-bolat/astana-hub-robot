"""Yandex SpeechKit, API v1 (REST): синхронное распознавание коротких аудио и синтез в MP3.

Сверено с документацией SpeechKit (aistudio.yandex.ru/docs/speechkit, 2026-10):
- STT: POST https://stt.api.cloud.yandex.net/speech/v1/stt:recognize
  query: lang (ru-RU / kk-KZ / en-US), format=lpcm, sampleRateHertz=16000, folderId (опц.);
  тело — сырой PCM без WAV-заголовка; лимиты 30 с / 1 МБ / mono; ответ {"result": "..."}.
  Автоопределение языка (lang=auto) есть только в API v3, в v1 его нет — см. `_stt_auto`.
- TTS: POST https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize (form-urlencoded)
  text, lang (ru-RU / kk-KK / en-US), voice, format=mp3, folderId (опц.); ответ — байты аудио.
  Голоса v1: ru — jane, filipp, ermil, omazh, zahar, marina, madi_ru; kk — amira, madi; en — john.
- Авторизация: заголовок `Authorization: Api-Key <ключ>`. folderId не нужен для ключа
  сервисного аккаунта.
"""

from __future__ import annotations

import asyncio

import httpx

from app.lang import KAZAKH_LETTERS, Lang, detect_lang
from app.speech.base import SpeechError, SttResult

STT_LANG = {"ru": "ru-RU", "kk": "kk-KZ", "en": "en-US"}
TTS_LANG = {"ru": "ru-RU", "kk": "kk-KK", "en": "en-US"}


class YandexSpeechKitProvider:
    name = "yandex"

    def __init__(
        self,
        api_key: str,
        folder_id: str | None,
        stt_url: str,
        tts_url: str,
        voices: dict[str, str],
        timeout_s: float = 10.0,
        client: httpx.AsyncClient | None = None,
        auto_lang_timeout_s: float = 3.0,
    ) -> None:
        self.folder_id = folder_id or None
        self.auto_lang_timeout_s = auto_lang_timeout_s
        self.stt_url = stt_url
        self.tts_url = tts_url
        self.voices = voices
        self.client = client or httpx.AsyncClient(
            timeout=timeout_s, headers={"Authorization": f"Api-Key {api_key}"}
        )

    async def aclose(self) -> None:
        await self.client.aclose()

    def voice_id(self, lang: Lang) -> str:
        return self.voices[lang]

    async def _recognize(self, pcm: bytes, sample_rate: int, lang: Lang) -> str:
        params = {
            "lang": STT_LANG[lang],
            "format": "lpcm",
            "sampleRateHertz": str(sample_rate),
            "topic": "general",
        }
        if self.folder_id:
            params["folderId"] = self.folder_id
        try:
            r = await self.client.post(self.stt_url, params=params, content=pcm)
        except httpx.HTTPError as exc:
            raise SpeechError(f"yandex stt network error: {type(exc).__name__}") from exc
        if r.status_code != 200:
            # Тело ответа об ошибке не содержит аудио — можно логировать
            raise SpeechError(f"yandex stt http {r.status_code}: {r.text[:200]}")
        try:
            return str(r.json().get("result", "")).strip()
        except ValueError as exc:
            raise SpeechError("yandex stt returned non-JSON") from exc

    async def stt(self, pcm: bytes, sample_rate: int, lang: Lang | None) -> SttResult:
        if lang:
            text = await self._recognize(pcm, sample_rate, lang)
            return SttResult(text=text, lang=lang, confidence=None)
        return await self._stt_auto(pcm, sample_rate)

    async def _stt_auto(self, pcm: bytes, sample_rate: int) -> SttResult:
        """В API v1 нет автоопределения языка: распознаём параллельно ru, kk и en и выбираем.

        Правила выбора (порядок важен):
        1. В kk-гипотезе есть специфичные казахские буквы → kk.
        2. ru-гипотеза непустая и по тексту выглядит русской/казахской → ru.
        3. en-гипотеза непустая и по тексту английская → en.
        4. Иначе — первая непустая гипотеза (ru, kk, en) или пустой текст.
        Все три запроса идут параллельно, но ждём не дольше `auto_lang_timeout_s` (~3 с): выбор
        делается из успевших, опоздавшие отменяются. Так общий ответ укладывается в STT_TIMEOUT_S
        (фабрика ставит auto_lang_timeout_s ≤ stt_timeout_s). Стоимость — x3. Если робот знает язык
        (кнопка RU/KZ/EN на экране), передавайте lang — будет один запрос.
        """
        langs: tuple[Lang, ...] = ("ru", "kk", "en")
        tasks = {
            asyncio.ensure_future(self._recognize(pcm, sample_rate, lang)): lang for lang in langs
        }
        try:
            done, _ = await asyncio.wait(set(tasks), timeout=self.auto_lang_timeout_s)
        finally:
            for task in tasks:
                if not task.done():
                    task.cancel()
        texts: dict[Lang, str] = {}
        errors: list[BaseException] = []
        for task in done:
            exc = task.exception()
            if exc is not None:
                errors.append(exc)
            else:
                texts[tasks[task]] = task.result()
        if not texts:
            if errors:
                raise SpeechError(f"yandex stt failed for all languages: {errors[0]}")
            raise TimeoutError(f"yandex stt: no language answered in {self.auto_lang_timeout_s}s")
        return choose_hypothesis(texts)

    async def tts(self, text: str, lang: Lang) -> bytes:
        data = {"text": text, "lang": TTS_LANG[lang], "voice": self.voices[lang], "format": "mp3"}
        if self.folder_id:
            data["folderId"] = self.folder_id
        try:
            r = await self.client.post(self.tts_url, data=data)
        except httpx.HTTPError as exc:
            raise SpeechError(f"yandex tts network error: {type(exc).__name__}") from exc
        if r.status_code != 200:
            raise SpeechError(f"yandex tts http {r.status_code}: {r.text[:200]}")
        if not r.content:
            raise SpeechError("yandex tts returned empty audio")
        return r.content


def choose_hypothesis(texts: dict[Lang, str]) -> SttResult:
    kk, ru, en = texts.get("kk", ""), texts.get("ru", ""), texts.get("en", "")
    if kk and any(ch in KAZAKH_LETTERS for ch in kk):
        return SttResult(text=kk, lang="kk")
    if ru and detect_lang(ru) in ("ru", "kk"):
        return SttResult(text=ru, lang="ru")
    if en and detect_lang(en) == "en":
        return SttResult(text=en, lang="en")
    for lang in ("ru", "kk", "en"):
        if texts.get(lang):
            return SttResult(text=texts[lang], lang=lang)  # type: ignore[arg-type]
    return SttResult(text="", lang="ru")
