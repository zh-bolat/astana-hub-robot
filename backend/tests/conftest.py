from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import BACKEND_DIR, Settings
from app.llm.base import LLMAnswer, LLMError, LLMRequest
from app.llm.fake import FakeProvider
from app.main import create_app

TOKEN = "test-token"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
SESSION = "6f1c2a9e-7d4b-4c1a-9f3e-2b8d5e6a7c10"
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 2048 + b"\xff\xd9"


class FailingProvider:
    name = "failing"
    model = "failing"

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        raise LLMError("boom")

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        raise LLMError("boom")


class SlowProvider:
    name = "slow"
    model = "slow"

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        await asyncio.sleep(5)
        return LLMAnswer(found=True, answer="late")

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        await asyncio.sleep(5)
        return LLMAnswer(found=True, answer="late")


class ScriptedProvider:
    """Возвращает заранее заданный ответ — для проверки постобработки."""

    name = "scripted"
    model = "scripted"

    def __init__(self, result: LLMAnswer) -> None:
        self.result = result
        self.requests: list[LLMRequest] = []

    async def answer(self, req: LLMRequest) -> LLMAnswer:
        self.requests.append(req)
        return self.result

    async def describe_image(self, req: LLMRequest) -> LLMAnswer:
        self.requests.append(req)
        return self.result


@pytest.fixture
def knowledge_dir(tmp_path: Path) -> Path:
    dst = tmp_path / "knowledge"
    shutil.copytree(BACKEND_DIR / "knowledge", dst)
    return dst


@pytest.fixture
def settings(tmp_path: Path, knowledge_dir: Path) -> Settings:
    return Settings(
        _env_file=None,
        robot_token=TOKEN,
        llm_provider="fake",
        vision_provider="fake",
        gemini_api_key=None,
        knowledge_dir=knowledge_dir,
        knowledge_reload_interval_s=0,
        database_url=f"sqlite:///{tmp_path / 'questions.db'}",
        rate_limit_per_min=1000,
        ask_timeout_s=2,
        look_timeout_s=2,
        stt_provider="fake",
        tts_provider="fake",
        yandex_api_key=None,
        elevenlabs_api_key=None,
        tts_cache_dir=tmp_path / "tts_cache",
        speech_rate_limit_per_min=1000,
    )


@pytest.fixture
def make_client(settings: Settings):
    clients: list[TestClient] = []

    def _make(text_llm=None, vision_llm=None, speech=None, **overrides) -> TestClient:
        s = settings.model_copy(update=overrides)
        app = create_app(s, text_llm or FakeProvider(), vision_llm or FakeProvider(), speech)
        c = TestClient(app)
        clients.append(c)
        return c

    yield _make
    for c in clients:
        c.close()


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()


def ask_body(question: str, **extra) -> dict:
    return {"question": question, "session_id": SESSION, **extra}
