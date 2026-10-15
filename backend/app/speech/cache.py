"""Кеш синтезированных фраз: LRU в памяти + (опционально) папка на диске, оба с лимитом размера.

Кешируется только синтез ответов робота (не аудио посетителей).
"""

from __future__ import annotations

import hashlib
import os
import threading
from collections import OrderedDict
from pathlib import Path

from app.logging_setup import logger


def cache_key(provider: str, voice: str, lang: str, text: str) -> str:
    return hashlib.sha256(f"{provider}\x00{voice}\x00{lang}\x00{text}".encode()).hexdigest()


class TtsCache:
    def __init__(self, memory_bytes: int, disk_dir: Path | None = None, disk_bytes: int = 0):
        self.memory_bytes = memory_bytes
        self.disk_dir = Path(disk_dir) if disk_dir and disk_bytes > 0 else None
        self.disk_bytes = disk_bytes
        self._mem: OrderedDict[str, bytes] = OrderedDict()
        self._mem_size = 0
        self._lock = threading.Lock()
        if self.disk_dir:
            try:
                self.disk_dir.mkdir(parents=True, exist_ok=True)
            except OSError as exc:
                logger.error("tts_cache_disk_disabled", extra={"error": str(exc)})
                self.disk_dir = None

    # --- память ---
    def _mem_put(self, key: str, audio: bytes) -> None:
        if len(audio) > self.memory_bytes:
            return
        with self._lock:
            old = self._mem.pop(key, None)
            if old is not None:
                self._mem_size -= len(old)
            self._mem[key] = audio
            self._mem_size += len(audio)
            while self._mem_size > self.memory_bytes and self._mem:
                _, evicted = self._mem.popitem(last=False)
                self._mem_size -= len(evicted)

    def _mem_get(self, key: str) -> bytes | None:
        with self._lock:
            audio = self._mem.get(key)
            if audio is not None:
                self._mem.move_to_end(key)
            return audio

    # --- диск ---
    def _path(self, key: str) -> Path:
        assert self.disk_dir is not None
        return self.disk_dir / f"{key}.mp3"

    def _disk_get(self, key: str) -> bytes | None:
        if not self.disk_dir:
            return None
        path = self._path(key)
        try:
            audio = path.read_bytes()
            os.utime(path)  # LRU по mtime
            return audio
        except OSError:
            return None

    def _disk_put(self, key: str, audio: bytes) -> None:
        if not self.disk_dir or len(audio) > self.disk_bytes:
            return
        try:
            tmp = self._path(key).with_suffix(".tmp")
            tmp.write_bytes(audio)
            tmp.replace(self._path(key))
            self._disk_evict()
        except OSError as exc:  # нет места и т.п. — кеш не должен ломать ответ
            logger.warning("tts_cache_write_failed", extra={"error": str(exc)})

    def _disk_evict(self) -> None:
        assert self.disk_dir is not None
        files = []
        total = 0
        for p in self.disk_dir.glob("*.mp3"):
            try:
                st = p.stat()
            except OSError:
                continue
            files.append((st.st_mtime, st.st_size, p))
            total += st.st_size
        if total <= self.disk_bytes:
            return
        files.sort()
        for _, size, p in files:
            if total <= self.disk_bytes:
                break
            try:
                p.unlink()
                total -= size
            except OSError:
                pass

    # --- API ---
    def get(self, key: str) -> bytes | None:
        audio = self._mem_get(key)
        if audio is not None:
            return audio
        audio = self._disk_get(key)
        if audio is not None:
            self._mem_put(key, audio)
        return audio

    def put(self, key: str, audio: bytes) -> None:
        self._mem_put(key, audio)
        self._disk_put(key, audio)
