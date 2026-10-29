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

# Ремарки игривой модели — «*смеётся*», «(улыбается)», «[подмигивает]», «*laughs*», «(күледі)» — не озвучиваются
# и не показываются. Смех словом («Ха-ха!», «Ха!», «Haha!») — часть речи и остаётся; слишком длинный
# («хахахахаха») сводится к «Ха-ха». Вырезаем только короткие куски (до 6 слов, без цифр) в
# звёздочках или скобках со словом действия или интонации; «(EXPO)», «(павильон C4.6)», «*на втором этаже*»
# остаются (у разметки потом снимаются только звёздочки). Квадратные скобки в речи не встречаются: короткая
# пометка в них («[curious]», «[warmly]») — тоже ремарка, её вырезаем без словаря.
_STAGE_SPAN = re.compile(r"\*([^*\n]{1,80})\*|\(([^()\n]{1,80})\)|\[([^\[\]\n]{1,80})\]")
_STAGE_WORD = re.compile(
    r"(?<!\w)(?:"
    # ru
    r"смеё|смея|смеюсь|смех|усмеха|улыба|улыбк|подмиг|хихик|хохоч|хохот|вздых|вздох|машет|машу|помаха|"
    r"кива|кивн|подпрыг|пританц|танцу|кружит|кружусь|мигает|мигаю|моргает|моргаю|пищит|жужжит|бип|радостн|"
    r"весело(?!\w)|гордо(?!\w)|игрив|задумчив|восторж|смущ|шёпот|шепот|шепч|хлопа|аплодир|потирает|сияет|сияю|"
    # kk
    r"күлед|күлді|күлемін|күлкі|күлім|жымия|жымиып|көз қыс|көзін қыс|қол бұлға|басын изе|"
    # en
    r"laugh|chuckl|giggl|smil|grins(?!\w)|grinning|wink|sighs(?!\w)|sighing|waves(?!\w)|waving|nods(?!\w)|"
    r"nodding|beep|boop|spins(?!\w)|spinning|danc|whisper|cheerful|happily|excitedly|playful|blush"
    r")",
    re.IGNORECASE,
)
_STAGE_MAX_WORDS = 6
_SQUARE_MAX_WORDS = 3
_DIGIT = re.compile(r"\d")
# Смех длиннее трёх слогов: «хахахахаха», «ха-ха-ха-ха», «hahahaha»
_LONG_LAUGH = re.compile(
    r"(?<!\w)(?:ха|хе|хи|ha|he|hi)(?:-?(?:ха|хе|хи|ha|he|hi)){3,}[хh]?(?!\w)", re.IGNORECASE
)
_BOLD = re.compile(r"\*{2,}")
_MARK = "\x00"  # место вырезанной ремарки, пока чистим знаки вокруг
# Ремарка в начале предложения: следующее слово — с большой буквы («*смеётся*, конечно!» → «Конечно!»)
_AFTER_MARK = re.compile(r"(^|[.!?…])([\s,;:—–\x00-]*\x00[\s,;:—–\x00-]*)(\w)")
_LEADING_PUNCT = re.compile(r"^[\s.,;:!?—–-]+")


def _is_stage(m: re.Match[str]) -> bool:
    inner = next(g for g in m.groups() if g is not None)
    words = len(inner.split())
    if words > _STAGE_MAX_WORDS or _DIGIT.search(inner):
        return False
    if m.group(0).startswith("[") and words <= _SQUARE_MAX_WORDS:
        return True
    return _STAGE_WORD.search(inner) is not None


def _short_laugh(m: re.Match[str]) -> str:
    """«Хахахахаха» → «Ха-ха», «hahahaha» → «haha»: регистр первой буквы сохраняется."""
    word = m.group(0)
    laugh = "haha" if word[0].lower() in "h" else "ха-ха"
    return laugh.capitalize() if word[0].isupper() else laugh


def _drop_stage(text: str) -> str:
    """Текст без ремарок, без осиротевших знаков вокруг них; длинный смех — коротким «Ха-ха»."""
    text = _LONG_LAUGH.sub(_short_laugh, _BOLD.sub("*", text))  # «**кафе**» → «*кафе*»
    out = _STAGE_SPAN.sub(lambda m: _MARK if _is_stage(m) else m.group(0), text)
    if out == text:
        return text
    out = _AFTER_MARK.sub(lambda m: m.group(1) + m.group(2) + m.group(3).upper(), out)
    out = _LEADING_PUNCT.sub("", out.replace(_MARK, " "))
    out = re.sub(r"\s+([.,!?;:])", r"\1", out)
    # «справа, *машет*, идёмте» → «справа, идёмте»; «справа, (улыбается).» → «справа.»;
    # «Конечно! *смеётся*. Кафе» → «Конечно! Кафе»
    out = re.sub(r"([,;:])(?:\s*[,;:])+", r"\1", out)
    out = re.sub(r"[,;:]+([.!?])", r"\1", out)
    return re.sub(r"([.!?…])\s*[.,;:]+", r"\1", out)


def clean_for_tts(text: str) -> str:
    text = _MD_LINK.sub(r"\1", text)
    text = _URL.sub("", text)
    text = _drop_stage(text)
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
