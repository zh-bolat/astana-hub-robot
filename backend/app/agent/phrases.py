"""Сборка фраз из потока текста модели + служебные метки.

Модель начинает ответ с меток в квадратных скобках (см. app/agent/prompts.py):
- эмоция лица: [neutral] [happy] [surprised] [sad] [excited] — выбирает сама модель, в том же
  ответе, поэтому выбор эмоции не добавляет ни запроса, ни задержки;
- [unknown] — ответа в базе нет (вопрос пишется в лог «без ответа», found=false).
Метки вырезаются из текста и никогда не озвучиваются (любые [слово] — тоже).

Фраза = законченное предложение (знак конца + пробел); остаток отдаётся в конце ответа модели.
"""

from __future__ import annotations

import re

EMOTIONS = ("neutral", "happy", "thinking", "surprised", "sad", "excited")
UNKNOWN_TAG = "unknown"

_TAG = re.compile(r"\[\s*([A-Za-z_]{1,20})\s*\]")
# Незакрытая метка в конце буфера: ждём продолжения
_OPEN_TAG = re.compile(r"\[\s*[A-Za-z_]{0,20}\s*$")
_SENTENCE_END = re.compile(r"[.!?…](?=\s)")
_SPLIT = re.compile(r"(?<=[.!?…])\s+")


class PhraseAssembler:
    def __init__(self) -> None:
        self.buffer = ""
        self.tags: list[str] = []  # метки по порядку появления (в нижнем регистре)

    def feed(self, delta: str) -> tuple[list[str], list[str]]:
        """Кусок текста → (новые метки, законченные предложения)."""
        self.buffer += delta
        new_tags = self._take_tags(final=False)
        last_end = None
        for m in _SENTENCE_END.finditer(self.buffer):
            last_end = m.end()
        if last_end is None:
            return new_tags, []
        ready, self.buffer = self.buffer[:last_end], self.buffer[last_end:]
        return new_tags, _split(ready)

    def flush(self) -> tuple[list[str], list[str]]:
        """Конец ответа модели (или перед вызовом тула): всё, что осталось, — последней фразой."""
        new_tags = self._take_tags(final=True)
        rest, self.buffer = self.buffer, ""
        return new_tags, _split(rest)

    def _take_tags(self, final: bool) -> list[str]:
        found = [m.group(1).lower() for m in _TAG.finditer(self.buffer)]
        if found:
            self.buffer = _TAG.sub(" ", self.buffer)
        if final:
            self.buffer = _OPEN_TAG.sub("", self.buffer)
        self.tags.extend(found)
        return found

    @property
    def pending_tag(self) -> bool:
        return bool(_OPEN_TAG.search(self.buffer))


def strip_tags(text: str) -> str:
    return _TAG.sub(" ", text)


def _split(text: str) -> list[str]:
    return [s.strip() for s in _SPLIT.split(text) if s.strip()]
