"""Распознавание с подсказкой разговора — только профиль kurultai; у Astana Hub запрос STT прежний."""

from __future__ import annotations

import asyncio
import json

import httpx
import numpy as np
import pytest

from app.kurultai.stt_context import LAST_ANSWER_CHARS, SETTING, stt_context
from app.live.services import SegmentedProviderSTT
from app.speech.base import SttResult
from app.speech.gemini_stt import stt_prompt
from tests.test_speech_providers import _chat, _openrouter

# Промпт STT Astana Hub до изменения (2026-11-06) — должен остаться побайтно тем же.
HUB_PROMPT_AUTO = (
    "Transcribe the speech in this audio verbatim. "
    "The speech is in Russian, Kazakh or English — detect which. "
    "Write Kazakh in Cyrillic with Kazakh letters (ә ғ қ ң ө ұ ү һ і). "
    'Return JSON {"lang": "ru"|"kk"|"en", "text": transcript}. '
    'If there is no intelligible speech (silence, noise, music), return "text": "". '
    "Output only the transcript: no translation, no comments, no timestamps."
)


# ---------- промпт STT ----------
@pytest.mark.parametrize("context", [None, "", "   "])
def test_prompt_without_context_is_unchanged(context):
    assert stt_prompt(None, context) == HUB_PROMPT_AUTO
    assert stt_prompt(None) == HUB_PROMPT_AUTO


def test_prompt_with_context_keeps_rules_and_forbids_inventing_words():
    p = stt_prompt("kk", "робот Курултая")
    assert p.startswith(stt_prompt("kk"))
    assert p.endswith("робот Курултая")
    assert "never add" in p


# ---------- подсказка Курултая ----------
def test_context_without_answer_is_setting():
    assert stt_context(None) == SETTING
    assert stt_context("  ") == SETTING
    assert "Құрылтай" in SETTING


def test_context_has_last_answer_tail():
    long = "начало " + "слово " * 200 + "закон вступит в силу первого января."
    ctx = stt_context(long)
    assert ctx.startswith(SETTING)
    assert ctx.endswith("закон вступит в силу первого января.»")
    assert "начало" not in ctx
    assert len(ctx) < len(SETTING) + LAST_ANSWER_CHARS + 60


# ---------- OpenRouter: контекст доходит до промпта ----------
def _openrouter_prompt(**kw) -> str:
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return _chat('{"lang": "ru", "text": "Когда вступит закон?"}')

    res = asyncio.run(_openrouter(handler).stt(b"\x01\x02" * 100, 16000, None, **kw))
    assert res.text == "Когда вступит закон?"
    return seen[0]["messages"][1]["content"][0]["text"]


def test_openrouter_stt_prompt_with_and_without_context():
    assert _openrouter_prompt() == HUB_PROMPT_AUTO
    assert _openrouter_prompt(context=stt_context("Закон подписан.")).endswith("«Закон подписан.»")


# ---------- STT по отрезкам ----------
class _Provider:
    name = "p"

    def __init__(self, supports: bool) -> None:
        if supports:
            self.supports_context = True
        self.kwargs: list[dict] = []

    async def stt(self, pcm, sample_rate, lang, **kw):
        self.kwargs.append(kw)
        return SttResult(text="текст", lang="ru", confidence=None)


def _speech() -> bytes:
    rng = np.random.default_rng(0)
    return (rng.integers(-8000, 8000, 16_000)).astype(np.int16).tobytes()


async def _segment(stt: SegmentedProviderSTT) -> str:
    frames = [f async for f in stt.run_stt(_speech())]
    return frames[-1].text


@pytest.mark.parametrize(
    ("supports", "context", "expected"),
    [
        (True, None, {}),  # профиль без подсказки (Astana Hub) — вызов как раньше
        (True, lambda: "", {}),
        (False, lambda: "подсказка", {}),  # провайдер без supports_context (ElevenLabs и др.)
        (True, lambda: "подсказка", {"context": "подсказка"}),
    ],
)
def test_segmented_stt_passes_context_only_when_set_and_supported(supports, context, expected):
    provider = _Provider(supports)
    stt = SegmentedProviderSTT(provider, None, sample_rate=16_000, timeout_s=2.0)
    stt.context = context
    assert asyncio.run(_segment(stt)) == "текст"
    assert provider.kwargs == [expected]


def test_segmented_stt_survives_broken_context():
    provider = _Provider(True)
    stt = SegmentedProviderSTT(provider, None, sample_rate=16_000, timeout_s=2.0)

    def broken() -> str:
        raise RuntimeError("boom")

    stt.context = broken
    assert asyncio.run(_segment(stt)) == "текст"
    assert provider.kwargs == [{}]
