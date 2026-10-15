"""Подготовка ответа к TTS: очистка от markdown/ссылок/эмодзи и разбиение на фразы ≤ 300 символов."""

from __future__ import annotations

import re

MAX_PHRASE_LEN = 300

_MD_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_URL = re.compile(r"(https?://|www\.)\S+", re.IGNORECASE)
_MD_CHARS = re.compile(r"[*_`#>|~]+")
_LIST_MARK = re.compile(r"^\s*(?:[-•]|\d+[.)])\s+", re.MULTILINE)
_EMOJI = re.compile(
    "["
    "\U0001f000-\U0001faff"  # пиктограммы, эмодзи, символы
    "\U00002600-\U000027bf"  # разное, dingbats
    "\U0001f1e6-\U0001f1ff"  # флаги
    "️‍"
    "]+"
)
_SPACES = re.compile(r"\s+")
_SENTENCE_END = re.compile(r"(?<=[.!?…])\s+")


def clean_for_tts(text: str) -> str:
    text = _MD_LINK.sub(r"\1", text)
    text = _URL.sub("", text)
    text = _LIST_MARK.sub("", text)
    text = _MD_CHARS.sub("", text)
    text = _EMOJI.sub("", text)
    text = _SPACES.sub(" ", text).strip()
    text = re.sub(r"\s+([.,!?;:])", r"\1", text)
    return text


def _split_long(chunk: str, limit: int) -> list[str]:
    """Режет слишком длинное предложение: по , ; : — затем по пробелам, в крайнем случае жёстко."""
    if len(chunk) <= limit:
        return [chunk]
    for sep in (", ", "; ", ": ", " — ", " - "):
        if sep in chunk:
            parts = chunk.split(sep)
            out: list[str] = []
            cur = ""
            for i, part in enumerate(parts):
                piece = part + (sep.rstrip() if i < len(parts) - 1 else "")
                cand = f"{cur} {piece}".strip() if cur else piece
                if len(cand) <= limit:
                    cur = cand
                else:
                    if cur:
                        out.append(cur)
                    cur = piece
            if cur:
                out.append(cur)
            if all(len(p) <= limit for p in out) and len(out) > 1:
                return out
            return [q for p in out for q in _split_long(p, limit)]
    words = chunk.split(" ")
    out, cur = [], ""
    for w in words:
        while len(w) > limit:  # одно «слово» длиннее лимита
            if cur:
                out.append(cur)
                cur = ""
            out.append(w[:limit])
            w = w[limit:]
        cand = f"{cur} {w}".strip() if cur else w
        if len(cand) <= limit:
            cur = cand
        else:
            out.append(cur)
            cur = w
    if cur:
        out.append(cur)
    return out


def split_phrases(text: str, limit: int = MAX_PHRASE_LEN) -> list[str]:
    """Фраза = предложение; предложения длиннее limit режутся дальше."""
    text = text.strip()
    if not text:
        return []
    phrases: list[str] = []
    for sentence in _SENTENCE_END.split(text):
        sentence = sentence.strip()
        if sentence:
            phrases.extend(p for p in _split_long(sentence, limit) if p)
    return phrases
