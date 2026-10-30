"""Профиль kurultai (app/profiles.py, app/kurultai): робот Курултая на том же сервере по своему токену.

Сеть не трогаем: снимок quryltai.kz — из JSON-фикстур (tests/fixtures/quryltai) через httpx.MockTransport,
модель — фейковый OpenAI-совместимый сервер (tests/test_chat.FakeModel)."""

from __future__ import annotations

import asyncio
import contextlib
import json
import re
import threading
import time
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
import uvicorn
from fastapi.testclient import TestClient
from sqlalchemy import text
from websockets.asyncio.client import connect
from websockets.exceptions import InvalidStatus

from app.agent.prompts import NOT_ADDRESSED_TAG
from app.config import Settings
from app.kurultai import prompts
from app.kurultai.agent import strip_citations
from app.kurultai.quryltai import (
    BILLS_PATH,
    COMMITTEES_PATH,
    DEPUTY_REQUESTS_PATH,
    PLANS_PATH,
    QuryltaiSync,
    build_snapshot,
    prompt_context,
    status_name,
)
from app.kurultai.websearch import is_latest_news_question, needs_web_search
from app.llm.fake import FakeProvider
from app.main import create_app
from app.profiles import ASTANA_HUB, KURULTAI, profile_for_token
from tests.conftest import AUTH, SESSION, TOKEN, ask_body, logged
from tests.test_chat import FakeModel, call, say
from tests.test_live import Fakes, LiveClient, _free_port

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "quryltai"
KURULTAI_TOKEN = "kurultai-test-token"
K_AUTH = {"Authorization": f"Bearer {KURULTAI_TOKEN}"}
K_SSE = {**K_AUTH, "Accept": "text/event-stream"}
UPDATED_MS = int(datetime(2026, 10, 30, 10, 0, tzinfo=UTC).timestamp() * 1000)  # 15:00 в Астане
HUB_MARKERS = (
    "Astana Hub",
    "where_is",
    "guide_to",
    "look()",
    "search_kb",
    "БАЗА ЗНАНИЙ",
    "ресепшен",
)


def fixture(name: str):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def fixture_data() -> dict:
    return build_snapshot(
        fixture("bills.json"),
        fixture("plans.json"),
        fixture("committees.json"),
        fixture("deputy_requests.json"),
        UPDATED_MS,
    )


class FakeQuryltai:
    """quryltai.kz по путям API; fail — отвечать ошибкой (status) или обрывом сети."""

    def __init__(self) -> None:
        self.status = 200
        self.down = False
        self.paths: list[str] = []
        self.files = {
            BILLS_PATH: "bills.json",
            PLANS_PATH: "plans.json",
            COMMITTEES_PATH: "committees.json",
            DEPUTY_REQUESTS_PATH: "deputy_requests.json",
        }

    def handle(self, request: httpx.Request) -> httpx.Response:
        path = request.url.raw_path.decode().removeprefix("/api")
        self.paths.append(path)
        if self.down:
            raise httpx.ConnectError("no network", request=request)
        if self.status != 200:
            return httpx.Response(self.status, text="boom")
        return httpx.Response(200, content=(FIXTURES / self.files[path]).read_bytes())

    def sync(self, cache: Path | None = None, clock=lambda: UPDATED_MS / 1000) -> QuryltaiSync:
        return QuryltaiSync(
            "https://quryltai.kz/api",
            timeout_s=5,
            cache_path=cache,
            transport=httpx.MockTransport(self.handle),
            clock=clock,
        )


def ready_sync(tmp_path: Path) -> QuryltaiSync:
    sync = FakeQuryltai().sync(tmp_path / "quryltai.json")
    assert asyncio.run(sync.refresh())
    return sync


# ---------- снимок quryltai.kz ----------
def test_status_names_in_words():
    assert status_name(5) == "на рассмотрении"
    assert status_name(9) == "подписан Президентом"
    assert status_name(12) == "одобрен во втором чтении"
    assert status_name(0) == "уточняется"


def test_snapshot_prompt_from_fixtures():
    out = prompt_context(fixture_data())
    assert out.startswith(
        "\n\nОфициальный локальный снимок quryltai.kz. Обновлён: 30.10.2026 15:00. "
    )
    assert "Всего законопроектов: 10.\n" in out
    # готовые итоги по статусам, в порядке первого появления (как LinkedHashMap в Java)
    assert (
        "Итоги по статусам (посчитано по 10 законопроектам, используй эти числа, не пересчитывай): "
        "на рассмотрении — 4; подписан Президентом — 2; отозван — 1; принят — 1; уточняется — 1; "
        "одобрен в первом чтении — 1.\n"
    ) in out
    assert (
        "Законопроект ID 100: О законопроекте номер 1; опубликован 20.10.2026; статус: на рассмотрении.\n"
        in out
    )
    # перевод строки в названии — пробел; нет даты — словами
    assert (
        "Законопроект ID 101: О внесении изменений в Кодекс об административных правонарушениях;"
        in out
    )
    assert "Законопроект ID 103: О законопроекте номер 4; опубликован дата не указана;" in out
    assert (
        "Законопроект ID 105: О законопроекте номер 6; опубликован 15.10.2026; статус: подписан Президентом."
        in out
    )
    assert out.count("Законопроект ID ") == 10
    # планы: не больше трёх недельных, основной — один
    assert out.count("Недельный план: ") == 3
    assert "Недельный план: ПЛАН МЕРОПРИЯТИЙ КУРУЛТАЯ 26 октября 2026 года.\n" in out
    assert out.count("Основной план мероприятий: ") == 1
    # комитеты: число, активные члены, пустое отчество и должность — без «null»
    assert "\nОфициальный состав комитетов (всего комитетов: 8):\n" in out
    assert (
        "Комитет по аграрным вопросам (членов: 3): Председателев0 Серік Рахметоллаұлы — Председатель Комитета; "
        "Заместителев0 Айжан — Заместитель председателя Комитета; Членов0 Ерлан Маратович.\n"
    ) in out
    assert "Выбывший" not in out
    # запросы: всего по API, в промпт — до 120
    assert "\nОфициальные депутатские запросы (всего 130):\n" in out
    assert (
        "20.10.2026 — ДС-125: Зорлық-зомбылықтың алдын алуға қатысты; авторы: Аймаганова Айжан Талгатовна.\n"
        in out
    )
    assert "19.10.2026 — ДС-124: Запрос о теме 124.\n" in out  # без авторов — без «авторы:»
    assert "18.10.2026 — : Запрос о теме 123; авторы: Аймаганова Айжан Талгатовна.\n" in out
    assert sum(1 for line in out.splitlines() if re.match(r"\d\d\.\d\d\.\d{4} — ", line)) == 120
    assert "Запрос о теме 5;" not in out and "Запрос о теме 6;" in out
    assert out.endswith(
        "Этот снимок является официальным, но для иных изменяемых фактов используй веб-поиск."
    )
    assert "null" not in out and "None" not in out


def test_snapshot_without_data_is_empty():
    assert prompt_context(None) == ""
    assert prompt_context({"bills": []}) == ""  # старая схема кеша (без комитетов и запросов)


def test_sync_refresh_saves_cache_and_keeps_last_snapshot_on_errors(tmp_path):
    fake = FakeQuryltai()
    cache = tmp_path / "data" / "quryltai.json"
    sync = fake.sync(cache)
    assert sync.prompt_context() == ""
    assert asyncio.run(sync.refresh())
    assert sorted(fake.paths) == sorted(fake.files)
    good = sync.prompt_context()
    assert good == prompt_context(fixture_data())
    assert cache.exists()

    fake.status = 500  # сайт ответил ошибкой — снимок прежний
    assert not asyncio.run(sync.refresh())
    assert sync.prompt_context() == good and "HTTP 500" in (sync.last_error or "")
    fake.status, fake.down = 200, True  # сети нет — снимок прежний
    assert not asyncio.run(sync.refresh())
    assert sync.prompt_context() == good

    restarted = fake.sync(cache)  # перезапуск сервера без сети — последний снимок с диска
    assert restarted.load_cache()
    assert restarted.prompt_context() == good
    asyncio.run(sync.aclose())
    asyncio.run(restarted.aclose())


def test_sync_broken_cache_file_is_ignored(tmp_path):
    cache = tmp_path / "quryltai.json"
    cache.write_text("{not json", encoding="utf-8")
    sync = FakeQuryltai().sync(cache)
    assert not sync.load_cache() and sync.prompt_context() == ""


def test_sync_schedule():
    now = UPDATED_MS / 1000
    sync = FakeQuryltai().sync(clock=lambda: now)
    assert sync.next_delay(3600, None, 300) == 300  # снимка нет — пробуем через retry
    sync.set_data(fixture_data())
    assert sync.next_delay(3600, True, 300) == 3600  # свежий — через час
    assert sync.next_delay(3600, False, 300) == 300  # неудача — через 5 минут
    stale = FakeQuryltai().sync(clock=lambda: now + 1800)
    stale.set_data(fixture_data())
    assert stale.next_delay(3600, None, 300) == 1800  # снимку полчаса — обновить через полчаса


def test_periodic_refreshes_at_start_without_snapshot():
    fake = FakeQuryltai()
    sync = fake.sync()

    async def scenario() -> None:
        task = asyncio.create_task(sync.run_periodic(3600, 300))
        for _ in range(100):
            if sync.data is not None:
                break
            await asyncio.sleep(0.01)
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        await sync.aclose()

    asyncio.run(scenario())
    assert sync.data is not None and len(fake.paths) == 4


# ---------- веб-поиск ----------
@pytest.mark.parametrize(
    "question",
    [
        "Какие последние новости Курултая?",
        "Что было вчера на заседании?",
        "Какие законы подписал Президент?",
        "Что обсуждают депутаты сегодня?",
        "Какие мероприятия запланированы на завтра?",
        "Когда ближайшее заседание?",
        "Кеше Құрылтайда не болды?",
        "Ертең қандай отырыс бар?",
        "Соңғы жаңалықтар қандай?",
    ],
)
def test_web_search_for_news_events_and_schedule(question):
    assert needs_web_search(question)


@pytest.mark.parametrize(
    "question",
    [
        "Сколько законопроектов на рассмотрении?",
        "Кто председатель комитета по бюджету?",
        "Расскажи о Курултае",
        "Что сейчас в парламенте?",
        "Какие депутатские запросы были по экологии?",
        "Қанша заң жобасы бар?",
        "",
    ],
)
def test_no_web_search_for_snapshot_questions(question):
    assert not needs_web_search(question)


def test_latest_news_question():
    assert is_latest_news_question("Какие новые законы приняли?")
    assert is_latest_news_question("Күн тәртібінде не бар?")
    assert not is_latest_news_question("Какие мероприятия завтра?")  # расписание, но не новости
    assert not is_latest_news_question(None)


def test_strip_citations():
    assert (
        strip_citations("Закон подписан ([akorda.kz](https://akorda.kz/ru/1)).")
        == "Закон подписан."
    )
    assert strip_citations("Подробнее [здесь](https://gov.kz/x) и всё.") == "Подробнее здесь и всё."
    assert strip_citations("Принят 20 октября (источник: quryltai.kz).") == "Принят 20 октября."
    assert strip_citations("Комитет (по бюджету) работает.") == "Комитет (по бюджету) работает."


# ---------- промпт ----------
def test_kurultai_prompt_has_persona_voice_rules_and_snapshot():
    snapshot = prompt_context(fixture_data())
    out = prompts.kurultai_instructions(snapshot)
    for marker in HUB_MARKERS:
        assert marker not in out, marker
    assert "ИИ-помощник Курултая Республики Казахстан" in out
    assert NOT_ADDRESSED_TAG in out and "{not_addressed_tag}" not in out
    assert "[neutral]" in out and "no_answer()" in out and "<visitor_message>" in out
    assert "[REPEAT]" not in out  # маркер приложения — заменён переспросом
    assert out.endswith(snapshot) and prompts.SNAPSHOT_NOTE in out
    assert prompts.NO_SNAPSHOT_NOTE in prompts.kurultai_instructions("")


def test_kurultai_texts_have_no_hub():
    texts = [
        *prompts.REFUSALS.values(),
        *prompts.BLOCKED_ANSWERS.values(),
        *prompts.UNAVAILABLE.values(),
        *prompts.QUICK_GREETINGS.values(),
        *(g for variants in prompts.GREETINGS.values() for g in variants),
    ]
    assert all("Hub" not in t and "хаб" not in t.lower() and "ресепшен" not in t for t in texts)


# ---------- профиль по токену ----------
def test_profile_for_token():
    s = Settings(_env_file=None, robot_token="hub", kurultai_robot_token="kur")
    assert profile_for_token(s, "hub") == ASTANA_HUB
    assert profile_for_token(s, "kur") == KURULTAI
    assert profile_for_token(s, "nope") is None and profile_for_token(s, None) is None
    off = Settings(_env_file=None, robot_token="hub")
    assert profile_for_token(off, "kur") is None and not off.kurultai_enabled()


def test_same_token_for_both_profiles_is_rejected(settings):
    with pytest.raises(RuntimeError, match="KURULTAI_ROBOT_TOKEN"):
        create_app(settings.model_copy(update={"kurultai_robot_token": TOKEN}), FakeProvider())


@pytest.fixture
def make_kclient(settings):
    """Приложение с обоими профилями: агент хаба и агент Курултая — каждый со своей фейковой моделью."""
    clients = []

    def _make(
        tmp_path: Path, model: FakeModel | None = None, hub: FakeModel | None = None, **overrides
    ):
        update = {"kurultai_robot_token": KURULTAI_TOKEN, "kurultai_web_search_always": False}
        s = settings.model_copy(update={**update, **overrides})
        app = create_app(
            s,
            FakeProvider(),
            FakeProvider(),
            chat_client=(hub or FakeModel()).client(),
            kurultai_chat_client=model.client() if model is not None else None,
            quryltai=ready_sync(tmp_path),
        )
        c = TestClient(app)
        clients.append(c)
        return c

    yield _make
    for c in clients:
        c.close()
        c.app.state.log_writer.wait_idle(5)
        c.app.state.engine.dispose()


def test_profile_endpoint(make_kclient, tmp_path):
    c = make_kclient(tmp_path)
    assert c.get("/v1/profile", headers=AUTH).json() == {"profile": "astana_hub"}
    assert c.get("/v1/profile", headers=K_AUTH).json() == {"profile": "kurultai"}
    for headers in (
        {},
        {"Authorization": "Bearer wrong"},
        {"Authorization": f"Basic {KURULTAI_TOKEN}"},
    ):
        r = c.get("/v1/profile", headers=headers)
        assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"
    assert c.get("/v1/health").status_code == 200  # без токена, как раньше


def test_kurultai_token_rejected_when_profile_off(client):
    assert client.get("/v1/profile", headers=AUTH).json() == {"profile": "astana_hub"}
    assert client.get("/v1/profile", headers=K_AUTH).status_code == 401
    r = client.post("/v1/chat", json=ask_body("Привет"), headers=K_AUTH)
    assert r.status_code == 401


def test_hub_knowledge_endpoints_are_not_for_kurultai(make_kclient, tmp_path):
    c = make_kclient(tmp_path)
    r = c.post("/v1/ask", json=ask_body("Где кафе?"), headers=K_AUTH)
    assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"
    assert c.get("/v1/places", headers=K_AUTH).status_code == 404
    assert c.get("/v1/quick-questions", headers=K_AUTH).status_code == 404
    # хаб — как раньше
    assert c.post("/v1/ask", json=ask_body("Где кафе?"), headers=AUTH).status_code == 200
    assert c.get("/v1/places", headers=AUTH).status_code == 200


def test_rate_limit_is_separate_per_profile(make_kclient, tmp_path):
    c = make_kclient(tmp_path, kb_rate_limit_per_min=2)
    for _ in range(2):
        assert c.get("/v1/profile", headers=AUTH).status_code == 200
    assert c.get("/v1/profile", headers=AUTH).status_code == 429
    assert c.get("/v1/profile", headers=K_AUTH).status_code == 200  # у Курултая свой бакет


def test_speech_endpoints_work_for_kurultai(make_kclient, tmp_path):
    c = make_kclient(tmp_path)
    r = c.post("/v1/tts", json={"text": "Сәлеметсіз бе!", "lang": "kk"}, headers=K_AUTH)
    assert r.status_code == 200 and r.content


# ---------- агент Курултая ----------
def _system(request: dict) -> str:
    content = request["messages"][0]["content"]
    return content[0]["text"] if isinstance(content, list) else content


def _tools(request: dict) -> list[str]:
    return [t["function"]["name"] for t in request.get("tools", [])]


def kevents(c, question: str, **extra) -> list[tuple[str, dict]]:
    with c.stream("POST", "/v1/chat", json=ask_body(question, **extra), headers=K_SSE) as r:
        assert r.status_code == 200
        body = r.read().decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out


def test_kurultai_chat_uses_own_prompt_snapshot_and_no_hub_tools(make_kclient, tmp_path):
    model = FakeModel(
        say("[neutral] Сейчас на рассмотрении четыре законопроекта. Рассказать подробнее?")
    )
    hub = FakeModel()
    c = make_kclient(tmp_path, model=model, hub=hub)
    r = c.post("/v1/chat", json=ask_body("Сколько законопроектов на рассмотрении?"), headers=K_AUTH)
    assert r.status_code == 200
    body = r.json()
    assert body["phrases"] == [
        "Сейчас на рассмотрении четыре законопроекта.",
        "Рассказать подробнее?",
    ]
    assert body["actions"] == [] and body["emotion"] == "neutral" and body["found"]
    assert not hub.requests  # модель хаба не звали
    req = model.requests[0]
    system = _system(req)
    for marker in HUB_MARKERS:
        assert marker not in system, marker
    assert (
        "ИИ-помощник Курултая" in system
        and "на рассмотрении — 4; подписан Президентом — 2" in system
    )
    assert _tools(req) == ["no_answer"]
    assert "plugins" not in req  # вопрос по снимку — без веб-поиска
    assert req["model"] == "anthropic/claude-haiku-5.5"
    turn = req["messages"][-1]["content"]
    assert "Сейчас: " in turn and "(Астана)" in turn and "<visitor_message>" in turn
    rows = logged(c)
    assert len(rows) == 1 and rows[0].endpoint == "chat" and rows[0].found
    assert rows[0].provider == "kurultai:openrouter:anthropic/claude-haiku-5.5"


def test_kurultai_news_question_uses_web_plugin(make_kclient, tmp_path):
    model = FakeModel(say("[neutral] Вчера Курултай одобрил два законопроекта."))
    c = make_kclient(tmp_path, model=model)
    events = kevents(c, "Что было вчера в Курултае?")
    assert [d["text"] for e, d in events if e == "phrase"] == [
        "Вчера Курултай одобрил два законопроекта."
    ]
    plugins = model.requests[0]["plugins"]
    assert len(plugins) == 1 and plugins[0]["id"] == "web" and plugins[0]["max_results"] == 3
    assert "quryltai.kz" in plugins[0]["search_prompt"]
    assert "веб-поиска" in model.requests[0]["messages"][-1]["content"]
    assert model.requests[0]["reasoning"] == {"enabled": False}  # остальной extra_body — как у хаба


def test_kurultai_web_search_always_on_every_question(make_kclient, tmp_path):
    model = FakeModel(say("[neutral] Восемь комитетов."))
    c = make_kclient(tmp_path, model=model, kurultai_web_search_always=True)
    kevents(c, "Сколько комитетов в Курултае?")
    assert model.requests[0]["plugins"][0]["id"] == "web"


def test_kurultai_web_search_can_be_disabled(make_kclient, tmp_path):
    model = FakeModel(say("[neutral] Актуальных официальных данных у меня нет."))
    c = make_kclient(tmp_path, model=model, kurultai_web_search=False)
    kevents(c, "Какие последние новости?")
    assert "plugins" not in model.requests[0]


def test_kurultai_web_citations_are_not_spoken(make_kclient, tmp_path):
    model = FakeModel(
        say("[neutral] Закон подписан 20 октября ([akorda.kz](https://akorda.kz/ru/news/1)).")
    )
    c = make_kclient(tmp_path, model=model)
    events = kevents(c, "Какие законы подписал Президент?")
    assert [d["text"] for e, d in events if e == "phrase"] == ["Закон подписан 20 октября."]


def test_kurultai_off_topic_no_answer_goes_to_unanswered_log(make_kclient, tmp_path):
    model = FakeModel(call("no_answer"))
    c = make_kclient(tmp_path, model=model)
    r = c.post("/v1/chat", json=ask_body("Какой рецепт плова лучше?"), headers=K_AUTH)
    body = r.json()
    assert body["answer"] == prompts.REFUSALS["ru"] and not body["found"]
    rows = logged(c, only_unanswered=True)
    assert [row.question for row in rows] == ["Какой рецепт плова лучше?"]
    assert rows[0].category == "out_of_scope" and rows[0].provider.startswith("kurultai:")


def test_kurultai_not_addressed_is_ignored(make_kclient, tmp_path):
    model = FakeModel(say(NOT_ADDRESSED_TAG))
    c = make_kclient(tmp_path, model=model)
    r = c.post("/v1/chat", json=ask_body("Пойдём, заседание уже начинается"), headers=K_AUTH)
    assert r.json()["ignored"] is True and r.json()["phrases"] == []


def test_kurultai_noise_is_ignored_without_model(make_kclient, tmp_path):
    model = FakeModel()
    c = make_kclient(tmp_path, model=model)
    r = c.post("/v1/chat", json=ask_body("Ммм..."), headers=K_AUTH)
    assert r.json()["ignored"] is True and not model.requests


def test_kurultai_quick_greeting_and_thanks(make_kclient, tmp_path):
    model = FakeModel()
    c = make_kclient(tmp_path, model=model)
    hello = c.post("/v1/chat", json=ask_body("Сәлеметсіз бе"), headers=K_AUTH).json()
    assert hello["answer"] == prompts.QUICK_GREETINGS["kk"]
    thanks = c.post("/v1/chat", json=ask_body("Спасибо"), headers=K_AUTH).json()
    assert thanks["answer"] == "Пожалуйста! Всегда рад помочь."
    assert not model.requests


def test_kurultai_hub_place_question_goes_to_model_without_actions(make_kclient, tmp_path):
    model = FakeModel(
        call("where_is", {"place_id": "place:cafe"}), say("[neutral] Кафе — на первом этаже.")
    )
    c = make_kclient(tmp_path, model=model)
    body = c.post("/v1/chat", json=ask_body("Где кафе?"), headers=K_AUTH).json()
    # инструмента where_is у Курултая нет: действия робота не уходят
    assert body["actions"] == []


def test_kurultai_greeting_by_model(make_kclient, tmp_path):
    model = FakeModel(say("[happy] Здравствуйте! Я ИИ-помощник Курултая. Чем помочь?"))
    c = make_kclient(tmp_path, model=model)
    body = c.post(
        "/v1/chat",
        json={"question": "", "session_id": SESSION, "lang": "kk", "event": "visitor_arrived"},
        headers=K_AUTH,
    ).json()
    assert body["phrases"][0] == "Здравствуйте!"
    turn = model.requests[0]["messages"][-1]["content"]
    assert "ИИ-помощника Курултая" in turn and "kk (казахский)" in turn
    assert "Текущее место робота" not in turn  # мест хаба нет
    assert logged(c) == []  # приветствие — не в журнал вопросов


@pytest.mark.parametrize("lang", ["ru", "kk"])
def test_kurultai_ready_greeting_without_model(make_kclient, tmp_path, lang):
    c = make_kclient(tmp_path)  # клиента модели нет (нет OPENROUTER_API_KEY)
    body = c.post(
        "/v1/chat",
        json={"question": "", "session_id": SESSION, "lang": lang, "event": "visitor_arrived"},
        headers=K_AUTH,
    ).json()
    assert body["answer"] in prompts.GREETINGS[lang]


def test_kurultai_without_model_says_unavailable_not_hub_answer(make_kclient, tmp_path):
    c = make_kclient(tmp_path)
    body = c.post("/v1/chat", json=ask_body("Кто председатель Курултая?"), headers=K_AUTH).json()
    assert body["answer"] == prompts.UNAVAILABLE["ru"] and not body["found"]


def test_kurultai_model_error_falls_back_to_unavailable(make_kclient, tmp_path):
    model = FakeModel(status=500)
    c = make_kclient(tmp_path, model=model)
    body = c.post("/v1/chat", json=ask_body("Сколько комитетов в Курултае?"), headers=K_AUTH).json()
    assert body["answer"] == prompts.UNAVAILABLE["ru"]


def test_kurultai_metrics_marked_with_profile(make_kclient, tmp_path):
    model = FakeModel(say("[neutral] В Курултае восемь комитетов."))
    c = make_kclient(tmp_path, model=model)
    c.post("/v1/chat", json=ask_body("Сколько комитетов в Курултае?"), headers=K_AUTH)
    c.post(
        "/v1/metrics/turn",
        json={"session_id": SESSION, "mode": "agent", "lang": "ru", "first_audio_ms": 900},
        headers=K_AUTH,
    )
    assert c.app.state.log_writer.wait_idle(5)
    with c.app.state.engine.connect() as conn:
        rows = conn.execute(text("SELECT kind, provider, extra FROM latency_events")).all()
    chat_rows = [r for r in rows if r.kind == "chat"]
    assert chat_rows and all(r.extra.get("profile") == "kurultai" for r in chat_rows)
    assert all(r.provider.startswith("kurultai:") for r in chat_rows)
    robot_rows = [r for r in rows if r.kind == "robot"]
    assert robot_rows and all(r.extra.get("profile") == "kurultai" for r in robot_rows)


def test_kurultai_memory_is_separate_from_hub(make_kclient, tmp_path):
    model = FakeModel(say("[neutral] Восемь комитетов."), say("[neutral] Председатель — Серік."))
    c = make_kclient(tmp_path, model=model)
    c.post("/v1/chat", json=ask_body("Сколько комитетов?"), headers=K_AUTH)
    c.post("/v1/chat", json=ask_body("А кто председатель первого?"), headers=K_AUTH)
    roles = [m["role"] for m in model.requests[1]["messages"]]
    assert roles == ["system", "user", "assistant", "user"]  # помнит разговор
    assert SESSION not in c.app.state.chat._recent  # память хаба не тронута


# ---------- Astana Hub без изменений ----------
def test_hub_unchanged_when_kurultai_enabled(make_kclient, tmp_path):
    hub = FakeModel(say("[happy] Кафе на первом этаже."))
    kur = FakeModel()
    c = make_kclient(tmp_path, model=kur, hub=hub)
    body = c.post("/v1/chat", json=ask_body("Расскажи про кафе"), headers=AUTH).json()
    assert body["phrases"] == ["Кафе на первом этаже."]
    assert not kur.requests
    system = _system(hub.requests[0])
    assert "Astana Hub" in system and "БАЗА ЗНАНИЙ" in system and "Курултай" not in system
    assert {"where_is", "guide_to", "look", "no_answer"} <= set(_tools(hub.requests[0]))
    assert "plugins" not in hub.requests[0]
    rows = logged(c)
    assert rows[0].provider == "openrouter:anthropic/claude-haiku-5.5"  # без префикса профиля
    with c.app.state.engine.connect() as conn:
        extras = (
            conn.execute(text("SELECT extra FROM latency_events WHERE kind = 'chat'"))
            .scalars()
            .all()
        )
    assert extras and all("profile" not in e for e in extras)


def test_hub_news_question_has_no_web_plugin(make_kclient, tmp_path):
    hub = FakeModel(say("[happy] Сегодня в хабе митап."))
    c = make_kclient(tmp_path, hub=hub)
    c.post("/v1/chat", json=ask_body("Какие новости сегодня?"), headers=AUTH)
    assert "plugins" not in hub.requests[0]


def test_hub_ready_greeting_unchanged(client):
    from app.agent.prompts import GREETINGS

    body = client.post(
        "/v1/chat",
        json={"question": "", "session_id": SESSION, "lang": "ru", "event": "visitor_arrived"},
        headers=AUTH,
    ).json()
    assert body["answer"] in GREETINGS["ru"]


# ---------- WS /v1/live в профиле kurultai ----------
@pytest.fixture
def kurultai_live(settings, tmp_path):
    servers = []

    def _start(model: FakeModel, script: list[str] | None = None):
        fakes = Fakes(script or [])
        s = settings.model_copy(
            update={
                "live_enabled": True,
                "live_turn_silence_s": 0.15,
                "live_stt_final_timeout_s": 0.6,
                "kurultai_robot_token": KURULTAI_TOKEN,
            }
        )
        app = create_app(
            s,
            FakeProvider(),
            FakeProvider(),
            chat_client=FakeModel().client(),
            kurultai_chat_client=model.client(),
            live_services=fakes,
            quryltai=ready_sync(tmp_path),
        )
        port = _free_port()
        server = uvicorn.Server(
            uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning", lifespan="off")
        )
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        deadline = time.time() + 10
        while not server.started and time.time() < deadline:
            time.sleep(0.02)
        servers.append((server, thread, app))
        return f"ws://127.0.0.1:{port}", app

    yield _start
    for server, thread, app in servers:
        server.should_exit = True
        thread.join(10)
        app.state.log_writer.wait_idle(5)
        app.state.engine.dispose()


@contextlib.asynccontextmanager
async def ksession(url: str, token: str = KURULTAI_TOKEN) -> AsyncIterator[LiveClient]:
    async with connect(
        f"{url}/v1/live?session_id={SESSION}&lang=ru",
        additional_headers={"Authorization": f"Bearer {token}"},
        open_timeout=5,
    ) as ws:
        client = LiveClient(ws)
        tasks = [asyncio.create_task(client.reader()), asyncio.create_task(client.mic())]
        try:
            await client.wait("ready")
            await client.send({"type": "hello", "lang": "ru", "aec": True})
            yield client
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)


def test_kurultai_live_turn_and_greeting(kurultai_live):
    model = FakeModel(
        say("[happy] Здравствуйте! Я ИИ-помощник Курултая."),
        say("[neutral] В Курултае восемь комитетов."),
    )
    url, app = kurultai_live(model, script=["Сколько комитетов в Курултае?"])

    async def scenario() -> None:
        async with ksession(url) as c:
            await c.send({"type": "visitor_arrived"})
            end = await c.wait("turn_end")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)
            c.speak(0.6)
            await c.wait("user_final")
            await c.wait("turn_end", count=2)
            assert [p["text"] for p in c.events("phrase")] == [
                "Здравствуйте!",
                "Я ИИ-помощник Курултая.",
                "В Курултае восемь комитетов.",
            ]

    asyncio.run(asyncio.wait_for(scenario(), 40))
    assert len(model.requests) == 2
    assert "ИИ-помощник Курултая" in _system(model.requests[1])
    assert _tools(model.requests[1]) == ["no_answer"]
    assert app.state.log_writer.wait_idle(5)
    with app.state.engine.connect() as conn:
        row = conn.execute(text("SELECT endpoint, provider FROM question_log")).one()
        extras = (
            conn.execute(text("SELECT extra FROM latency_events WHERE kind = 'live'"))
            .scalars()
            .all()
        )
    assert row.endpoint == "live" and row.provider.startswith("kurultai:")
    assert extras and all(e.get("profile") == "kurultai" for e in extras)


def test_kurultai_live_rejects_wrong_token(kurultai_live):
    url, _ = kurultai_live(FakeModel())

    async def scenario() -> None:
        with pytest.raises(InvalidStatus):
            async with ksession(url, token="wrong"):
                pass

    asyncio.run(asyncio.wait_for(scenario(), 20))
