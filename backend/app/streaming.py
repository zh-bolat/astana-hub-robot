"""Инкрементальный разбор JSON-ответа модели при стриминге (шаг 4.4).

Модель отвечает JSON в порядке полей found → lang → answer → sources (propertyOrdering).
Парсер получает куски текста и отдаёт:
- `found` и `lang`, как только они появились;
- готовые предложения из поля answer — по мере того, как модель их дописывает;
- полный JSON в конце (для sources).
"""

from __future__ import annotations

import json
import re

_CATEGORY = re.compile(r'"category"\s*:\s*"(kb|small_talk|general|out_of_scope)"')
_FOUND = re.compile(r'"found"\s*:\s*(true|false)')
_LANG = re.compile(r'"lang"\s*:\s*"(ru|kk|en)"')
_ANSWER = re.compile(r'"answer"\s*:\s*"')
# Конец предложения: знак + пробел (последнее предложение отдаётся при закрытии строки)
_SENTENCE_END = re.compile(r"[.!?…](?=\s)")
_ESCAPES = {'"': '"', "\\": "\\", "/": "/", "b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t"}


class AnswerStreamParser:
    def __init__(self) -> None:
        self.buffer = ""
        self.found: bool | None = None
        self.category: str | None = None
        self.lang: str | None = None
        self.answer = ""  # раскодированная часть answer
        self.answer_closed = False
        self._answer_start: int | None = None  # индекс в buffer начала содержимого строки
        self._scan_pos = 0  # докуда раскодирован buffer
        self._emitted = 0  # докуда answer уже отдан предложениями

    def feed(self, chunk: str) -> list[str]:
        """Добавляет кусок; возвращает новые законченные предложения ответа."""
        self.buffer += chunk
        if self.category is None and (m := _CATEGORY.search(self.buffer)):
            self.category = m.group(1)
        if self.found is None and (m := _FOUND.search(self.buffer)):
            self.found = m.group(1) == "true"
        if self.lang is None and (m := _LANG.search(self.buffer)):
            self.lang = m.group(1)
        if self._answer_start is None and (m := _ANSWER.search(self.buffer)):
            self._answer_start = self._scan_pos = m.end()
        if self._answer_start is not None and not self.answer_closed:
            self._decode()
        return self._take_sentences(final=self.answer_closed)

    def _decode(self) -> None:
        buf, i, out = self.buffer, self._scan_pos, []
        while i < len(buf):
            ch = buf[i]
            if ch == '"':
                self.answer_closed = True
                i += 1
                break
            if ch == "\\":
                if i + 1 >= len(buf):
                    break  # экранирование разрезано между кусками — ждём продолжения
                nxt = buf[i + 1]
                if nxt == "u":
                    if i + 6 > len(buf):
                        break
                    code = _hex(buf[i + 2 : i + 6])
                    if code is None:  # битое \u — пропускаем
                        i += 6
                        continue
                    if 0xD800 <= code <= 0xDBFF:
                        # суррогатная пара 😀 → один символ; вторая половина может
                        # ещё не прийти — ждём продолжения
                        tail = buf[i + 6 : i + 8]
                        if i + 12 > len(buf) and "\\u".startswith(tail):
                            break
                        low = _hex(buf[i + 8 : i + 12]) if buf[i + 6 : i + 8] == "\\u" else None
                        if low is not None and 0xDC00 <= low <= 0xDFFF:
                            out.append(chr(0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00)))
                            i += 12
                        else:
                            i += 6  # одиночная высокая половина — отбрасываем
                        continue
                    if 0xDC00 <= code <= 0xDFFF:
                        i += 6  # одиночная низкая половина — отбрасываем
                        continue
                    out.append(chr(code))
                    i += 6
                    continue
                out.append(_ESCAPES.get(nxt, nxt))
                i += 2
                continue
            out.append(ch)
            i += 1
        self.answer += "".join(out)
        self._scan_pos = i

    def _take_sentences(self, final: bool) -> list[str]:
        pending = self.answer[self._emitted :]
        if final:
            self._emitted = len(self.answer)
            return _split(pending)
        last_end = None
        for m in _SENTENCE_END.finditer(pending):
            last_end = m.end()
        if last_end is None:
            return []
        self._emitted += last_end
        return _split(pending[:last_end])

    def finish(self) -> list[str]:
        """Конец потока: отдаёт остаток ответа (если строка не была закрыта)."""
        return self._take_sentences(final=True)

    def full_json(self) -> dict:
        text = self.buffer.strip()
        start, end = text.find("{"), text.rfind("}")
        if start == -1 or end == -1:
            return {}
        try:
            return json.loads(text[start : end + 1])
        except ValueError:
            return {}


def _hex(s: str) -> int | None:
    if len(s) != 4 or any(c not in "0123456789abcdefABCDEF" for c in s):
        return None
    return int(s, 16)


_SPLIT = re.compile(r"(?<=[.!?…])\s+")


def _split(text: str) -> list[str]:
    return [s.strip() for s in _SPLIT.split(text) if s.strip()]
