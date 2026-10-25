"""Язык в live: голос по каждой фразе ответа, язык реплики по тексту и STT; нарезка фраз для синтеза."""

from __future__ import annotations

import pytest

from app.lang import tts_lang, utterance_lang
from app.live.conversation import tts_chunks


def test_tts_chunks_split_long_phrase_by_clause():
    text = "Кафе бірінші қабатта, кіреберістің оң жағында, лифттің жанында орналасқан."
    chunks = tts_chunks(text, 40)
    assert " ".join(chunks) == text and len(chunks) >= 2
    assert all(len(c) <= 45 for c in chunks)
    assert tts_chunks("Короткая фраза.", 40) == ["Короткая фраза."]
    assert tts_chunks("Длиннаяфразабеззапятыхисовсембезпробеловвообщедлиннее.", 20) == [
        "Длиннаяфразабеззапятыхисовсембезпробеловвообщедлиннее."
    ]


@pytest.mark.parametrize(
    ("phrase", "fallback", "voice"),
    [
        ("Кафе бірінші қабатта.", "ru", "kk"),  # казахский текст — всегда kk, даже в русском ходе
        ("Кафе бар ма?", "ru", "kk"),
        ("Кафе на первом этаже.", "ru", "ru"),
        ("Кафе на первом этаже.", "en", "ru"),
        ("Кафе.", "kk", "kk"),
        ("The cafe is on the first floor.", "ru", "en"),
        ("Astana Hub!", "kk", "kk"),
    ],
)
def test_tts_voice_by_each_phrase(phrase, fallback, voice):
    assert tts_lang(phrase, fallback) == voice


@pytest.mark.parametrize(
    ("text", "stt", "session", "lang"),
    [
        ("Кафе қайда?", None, "ru", "kk"),
        ("Где кафе?", "ru", "kk", "ru"),  # STT услышал русский — не язык экрана
        ("Кафе бар", "kk", "ru", "kk"),
        ("Где кафе?", None, "kk", "kk"),  # STT не сказал (или сказал не ru/kk/en) — язык сессии
        ("Where is the cafe?", None, "ru", "en"),
        ("123", None, "kk", "kk"),
    ],
)
def test_utterance_lang(text, stt, session, lang):
    assert utterance_lang(text, stt, session) == lang
