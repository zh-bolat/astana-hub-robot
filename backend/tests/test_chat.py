"""Агентский чат /v1/chat: настоящий Agents SDK, модель — фейковый OpenAI-совместимый сервер
(httpx.MockTransport отдаёт стрим chat.completions с текстом и вызовами инструментов)."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
from agents import set_tracing_disabled
from agents.tracing import TracingProcessor, add_trace_processor, get_trace_provider
from openai import AsyncOpenAI
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.agent.prompts import GREETINGS, LOOKING
from app.agent.service import build_chat_client
from app.config import Settings
from app.db.models import MessageRow
from app.prompts import REFUSALS
from app.schemas import ChatRequest
from app.text import split_phrases
from tests.conftest import AUTH, SESSION, ask_body, logged

SSE = {**AUTH, "Accept": "text/event-stream"}
EVAL_DIR = Path(__file__).resolve().parent.parent / "eval"


def say(text: str) -> dict:
    return {"text": text}


def call(name: str, args: dict | None = None, text: str = "") -> dict:
    return {"text": text, "tool": (name, args or {})}


class FakeModel:
    """Отвечает на запросы /chat/completions по сценарию: один элемент — один ответ модели."""

    def __init__(self, *turns: dict, status: int = 200, delay_s: float = 0.0) -> None:
        self.turns = list(turns)
        self.status = status
        self.delay_s = delay_s
        self.requests: list[dict] = []

    async def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(json.loads(request.content))
        if self.delay_s:
            await asyncio.sleep(self.delay_s)
        if self.status != 200:
            return httpx.Response(self.status, json={"error": {"message": "boom"}})
        turn = self.turns.pop(0) if self.turns else say("")
        return httpx.Response(
            200, content=_sse(turn), headers={"content-type": "text/event-stream"}
        )

    def client(self) -> AsyncOpenAI:
        settings = Settings(
            _env_file=None,
            robot_token="test",
            openrouter_api_key="test",
            openrouter_base_url="http://fake-openrouter/api/v1",
        )
        client = build_chat_client(settings, transport=httpx.MockTransport(self.handle))
        assert client is not None
        return client


def _chunk(delta: dict, finish: str | None = None) -> dict:
    return {
        "id": "gen-1",
        "object": "chat.completion.chunk",
        "created": 0,
        "model": "anthropic/claude-haiku-5.5",
        "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
    }


def _sse(turn: dict) -> bytes:
    chunks = [_chunk({"role": "assistant", "content": ""})]
    text = turn.get("text", "")
    chunks += [_chunk({"content": text[i : i + 15]}) for i in range(0, len(text), 15)]
    tool = turn.get("tool")
    if tool:
        name, args = tool
        chunks.append(
            _chunk(
                {
                    "tool_calls": [
                        {
                            "index": 0,
                            "id": f"call_{name}",
                            "type": "function",
                            "function": {"name": name, "arguments": json.dumps(args)},
                        }
                    ]
                }
            )
        )
    chunks.append(_chunk({}, "tool_calls" if tool else "stop"))
    chunks.append(
        {
            **_chunk({}),
            "choices": [],
            "usage": {
                "prompt_tokens": 4500,
                "completion_tokens": 40,
                "total_tokens": 4540,
                "prompt_tokens_details": {"cached_tokens": 4400},
            },
        }
    )
    body = "".join(f"data: {json.dumps(c, ensure_ascii=False)}\n\n" for c in chunks)
    return (body + "data: [DONE]\n\n").encode()


def events(client, question: str, with_emotion: bool = False, **extra) -> list[tuple[str, dict]]:
    """События SSE; emotion (v1.1) — только с with_emotion=True."""
    with client.stream("POST", "/v1/chat", json=ask_body(question, **extra), headers=SSE) as r:
        assert r.status_code == 200
        body = r.read().decode()
    out = []
    for block in body.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.splitlines())
        out.append((lines["event"], json.loads(lines["data"])))
    return out if with_emotion else [e for e in out if e[0] != "emotion"]


def messages(client) -> list[tuple[str, str, str | None]]:
    assert client.app.state.log_writer.wait_idle(5)
    with Session(client.app.state.engine) as s:
        rows = s.scalars(
            select(MessageRow).where(MessageRow.session_id == SESSION).order_by(MessageRow.id)
        )
        return [(r.role, r.content, r.tool_name) for r in rows]


def chat_client(make_client, model: FakeModel, **overrides):
    return make_client(chat_client=model.client(), **overrides)


def test_answer_is_streamed_logged_and_remembered(make_client):
    model = FakeModel(
        say("Гостевая сеть называется AstanaHub-Guest. Пароль на табличке у ресепшена.")
    )
    client = chat_client(make_client, model)

    evs = events(client, "Какой пароль от Wi-Fi?", lang="ru")

    assert evs == [
        ("phrase", {"text": "Гостевая сеть называется AstanaHub-Guest."}),
        ("phrase", {"text": "Пароль на табличке у ресепшена."}),
        ("done", {**evs[-1][1], "lang": "ru", "found": True, "sources": [], "actions": []}),
    ]
    row = logged(client)[0]
    assert (row.endpoint, row.category, row.found) == ("chat", "agent", True)
    assert row.provider == "openrouter:anthropic/claude-haiku-5.5"
    assert messages(client) == [
        ("visitor", "Какой пароль от Wi-Fi?", None),
        ("assistant", evs[0][1]["text"] + " " + evs[1][1]["text"], None),
    ]


def test_request_uses_haiku_cache_and_no_reasoning(make_client):
    model = FakeModel(say("Здравствуйте."))
    client = chat_client(make_client, model)
    events(client, "Расскажи о хабе", lang="ru", place_id="reception")

    req = model.requests[0]
    assert req["model"] == "anthropic/claude-haiku-5.5"
    assert req["stream"] is True and req["max_tokens"] == 400
    assert req["cache_control"] == {"type": "ephemeral"}
    assert req["reasoning"] == {"enabled": False}
    assert {t["function"]["name"] for t in req["tools"]} == {
        "where_is",
        "guide_to",
        "look",
        "no_answer",
    }
    system, user = req["messages"]
    # точка кеша сразу после правил и базы — общая для всех разговоров
    [block] = system["content"]
    assert block["cache_control"] == {"type": "ephemeral"}
    assert "БАЗА ЗНАНИЙ" in block["text"] and "place:cafe" in block["text"]
    assert "<visitor_message>\nРасскажи о хабе\n</visitor_message>" in user["content"]
    assert "Текущее место робота: place:reception" in user["content"]


def test_guide_to_sends_action_and_drops_preamble(make_client):
    model = FakeModel(
        call("guide_to", {"place_id": "place:cafe"}, text="The user wants the cafe. "),
        say("Провожаю вас в кафе, идите за мной."),
    )
    client = chat_client(make_client, model)

    evs = events(client, "Проводи меня в кафе", lang="ru")

    assert [e for e, _ in evs] == ["action", "phrase", "done"]
    assert evs[0][1] == {"type": "guide_to", "place_id": "cafe"}
    assert evs[1][1]["text"] == "Провожаю вас в кафе, идите за мной."
    done = evs[-1][1]
    assert done["actions"] == [{"type": "guide_to", "place_id": "cafe"}]
    assert done["sources"] == ["place:cafe"]
    tool_result = model.requests[1]["messages"][-1]
    assert tool_result["role"] == "tool" and '"ok": true' in tool_result["content"]
    roles = messages(client)
    assert roles[1][0] == "tool" and roles[1][2] == "guide_to"
    assert roles[2] == ("assistant", "Провожаю вас в кафе, идите за мной.", None)


def test_place_by_alias_resolves_to_map_point(make_client):
    """Модель передала «elevator» вместо "place:lift" — действие уходит с id точки карты."""
    model = FakeModel(call("guide_to", {"place_id": "elevator"}), say("Провожаю к лифту."))
    client = chat_client(make_client, model)

    evs = events(client, "Take me to the elevator", lang="en")

    assert evs[0] == ("action", {"type": "guide_to", "place_id": "lift"})
    assert evs[-1][1]["actions"] == [{"type": "guide_to", "place_id": "lift"}]
    assert evs[-1][1]["sources"] == ["place:lift"]
    result = json.loads(model.requests[1]["messages"][-1]["content"])
    assert (
        result["ok"] is True and result["place_id"] == "place:lift" and result["name"] == "Elevator"
    )


def test_unknown_place_is_reported_to_model(make_client):
    model = FakeModel(call("where_is", {"place_id": "place:mars"}), say("Такого места я не знаю."))
    client = chat_client(make_client, model)

    evs = events(client, "Где Марс?", lang="ru")

    assert [e for e, _ in evs] == ["phrase", "done"]
    assert evs[-1][1]["actions"] == []
    result = json.loads(model.requests[1]["messages"][-1]["content"])
    assert result["ok"] is False and "place:cafe" in [p["id"] for p in result["places"]]


def test_no_answer_stops_with_refusal(make_client):
    model = FakeModel(call("no_answer"))
    client = chat_client(make_client, model)

    evs = events(client, "Сколько стоит аренда офиса?", lang="ru")

    assert evs[0] == ("phrase", {"text": REFUSALS["ru"].split(". ")[0] + "."})
    assert evs[-1][1]["found"] is False
    assert len(model.requests) == 1  # после no_answer модель больше не зовём
    row = logged(client, only_unanswered=True)[0]
    assert (row.endpoint, row.category) == ("chat", "out_of_scope")


def test_look_action(make_client):
    model = FakeModel(call("look"))
    client = chat_client(make_client, model)

    evs = events(client, "Что это за комната?", lang="ru")

    assert evs[0] == ("action", {"type": "look", "place_id": None})
    assert evs[1] == ("phrase", {"text": LOOKING["ru"]})
    assert len(model.requests) == 1


def test_history_reaches_model(make_client):
    model = FakeModel(
        call("where_is", {"place_id": "place:cafe"}),
        say("Кафе на первом этаже."),
        call("guide_to", {"place_id": "place:cafe"}),
        say("Провожаю."),
    )
    client = chat_client(make_client, model)
    client.post(
        f"/v1/sessions/{SESSION}/messages",
        json={
            "kind": "where_am_i",
            "question": "Где я?",
            "answer": "Вы у ресепшена.",
            "lang": "ru",
        },
        headers=AUTH,
    )
    events(client, "Где кафе?", lang="ru")
    assert client.app.state.log_writer.wait_idle(5)
    events(client, "Проводи туда", lang="ru")

    convo = model.requests[2]["messages"][1:]
    assert [m["role"] for m in convo] == ["user", "assistant", "user", "assistant", "user"]
    assert "Где я?" in convo[0]["content"] and convo[1]["content"] == "Вы у ресепшена."
    assert "Где кафе?" in convo[2]["content"] and convo[3]["content"] == "Кафе на первом этаже."
    assert "Проводи туда" in convo[4]["content"]


def test_other_sessions_are_not_mixed(make_client):
    model = FakeModel(say("Первый."), say("Второй."))
    client = chat_client(make_client, model)
    events(client, "Вопрос один", lang="ru")
    assert client.app.state.log_writer.wait_idle(5)
    events(client, "Вопрос два", lang="ru", session_id="other-session")
    assert len(model.requests[1]["messages"]) == 2  # system + текущая реплика


def test_smalltalk_does_not_call_model(make_client):
    model = FakeModel()
    client = chat_client(make_client, model)

    evs = events(client, "Привет", lang="ru")

    assert evs[0][0] == "phrase" and evs[-1][0] == "done"
    assert model.requests == []
    assert logged(client)[0].category == "small_talk"


def test_blocked_answer_is_replaced(make_client):
    model = FakeModel(say("Скидки тут: evil-promo.com. Успейте."))
    client = chat_client(make_client, model)

    evs = events(client, "Есть скидки?", lang="ru")

    texts = " ".join(d["text"] for e, d in evs if e == "phrase")
    assert "evil-promo" not in texts and "Astana Hub" in texts
    assert evs[-1][1]["found"] is False
    assert logged(client)[0].category == "blocked"


def test_model_error_falls_back_to_ask(make_client):
    model = FakeModel(status=500)
    client = chat_client(make_client, model)

    evs = events(client, "Какой пароль от Wi-Fi?", lang="ru")

    assert evs[0][0] == "phrase" and evs[-1][0] == "done"
    assert evs[-1][1]["actions"] == []
    assert logged(client)[0].endpoint == "ask"
    assert [m[0] for m in messages(client)] == ["visitor", "assistant"]


def test_timeout_before_first_phrase(make_client):
    model = FakeModel(say("Поздно."), delay_s=2)
    client = chat_client(make_client, model, agent_timeout_s=0.3)

    evs = events(client, "Какой пароль от Wi-Fi?", lang="ru")

    assert evs == [
        (
            "error",
            {**evs[0][1], "error": {"code": "timeout", "message": evs[0][1]["error"]["message"]}},
        )
    ]
    assert logged(client) == []


def test_json_mode(make_client):
    model = FakeModel(call("guide_to", {"place_id": "cafe"}), say("Провожаю вас в кафе."))
    client = chat_client(make_client, model)

    r = client.post("/v1/chat", json=ask_body("Проводи в кафе", lang="ru"), headers=AUTH)

    assert r.status_code == 200
    body = r.json()
    assert body["phrases"] == ["Провожаю вас в кафе."]
    assert body["actions"] == [{"type": "guide_to", "place_id": "cafe"}]
    assert body["found"] is True and body["request_id"] == r.headers["x-request-id"]


def test_chat_off_answers_like_ask(client):
    r = client.post("/v1/chat", json=ask_body("Какой пароль от Wi-Fi?"), headers=AUTH)
    assert r.status_code == 200
    assert r.json()["actions"] == [] and r.json()["phrases"]
    assert client.app.state.chat.name == "off"


def test_chat_requires_token(client):
    assert client.post("/v1/chat", json=ask_body("Привет")).status_code == 401


def test_cut_stream_is_logged_as_interrupted(make_client):
    model = FakeModel(say("Кафе на первом этаже. Работает с девяти. Там есть выпечка."))
    client = chat_client(make_client, model)
    chat = client.app.state.chat

    async def run() -> None:
        stream = chat.chat_stream(ChatRequest(**ask_body("Где кафе?", lang="ru")), "req_cut")
        assert (await stream.__anext__())[0] == "emotion"
        assert (await stream.__anext__())[0] == "phrase"
        await stream.aclose()

    asyncio.run(run())

    row = logged(client)[0]
    assert row.interrupted is True and row.answer == "Кафе на первом этаже."
    assert messages(client)[-1] == ("assistant", "Кафе на первом этаже.", None)


def test_history_does_not_wait_for_db(make_client):
    """Следующий вопрос приходит сразу после done, раньше фоновой записи в БД."""
    model = FakeModel(say("Кафе на первом этаже."), say("Провожаю."))
    client = chat_client(make_client, model)
    client.app.state.log_writer.submit_job = lambda job, name="job": True  # БД «не успевает»

    events(client, "Где кафе?", lang="ru")
    events(client, "Проводи туда", lang="ru")

    convo = model.requests[1]["messages"][1:]
    assert [m["role"] for m in convo] == ["user", "assistant", "user"]
    assert convo[1]["content"] == "Кафе на первом этаже."


def test_history_survives_restart(make_client):
    first = FakeModel(say("Кафе на первом этаже."))
    client = chat_client(make_client, first)
    events(client, "Где кафе?", lang="ru")
    assert client.app.state.log_writer.wait_idle(5)

    second = FakeModel(say("Провожаю."))
    restarted = chat_client(make_client, second)  # новый процесс: кеш пустой, память — из БД
    events(restarted, "Проводи туда", lang="ru")

    convo = second.requests[0]["messages"][1:]
    assert [m["role"] for m in convo] == ["user", "assistant", "user"]
    assert "Где кафе?" in convo[0]["content"]


def test_narration_is_not_spoken(make_client):
    model = FakeModel(
        say(
            "The visitor asks where to go next. Посетитель просит проводить. "
            "По правилам я могу вызвать guide_to. Куда вас проводить?"
        )
    )
    client = chat_client(make_client, model)

    evs = events(client, "Проводи меня", lang="ru")

    assert [d["text"] for e, d in evs if e == "phrase"] == ["Куда вас проводить?"]


def test_translate_prefix_is_cut_answer_is_kept(make_client):
    # Haiku на проде: «Translate to Kazakh: EXPO жерасты паркингі…» — префикс не озвучиваем, ответ — да
    model = FakeModel(say("Translate to Kazakh: EXPO жерасты паркингі, шамамен 3000 орын. Перевод: Келіңіз!"))
    client = chat_client(make_client, model)

    evs = events(client, "Көлікті қайда қоюға болады?", lang="kk")

    assert [d["text"] for e, d in evs if e == "phrase"] == ["EXPO жерасты паркингі, шамамен 3000 орын.", "Келіңіз!"]


def test_reasoning_about_the_base_is_not_spoken(make_client):
    # Haiku на проде рассуждал вслух про поиск и базу — эти фразы не озвучиваем, ответ — да
    model = FakeModel(
        say(
            "Это вопрос о хабе, нужно поискать в базе. Вопрос: часы работы. Факт есть в базе: 9:00–18:30. "
            "Should I search? Answer directly. Хотя по базе часы такие. "
            "В базе данных о камере хранения нет. Администрация работает по будням с 9:00 до 18:30. "
            "Интернет на базе Технопарка входит в пакет."
        )
    )
    client = chat_client(make_client, model)

    evs = events(client, "Во сколько работает хаб?", lang="ru")

    assert [d["text"] for e, d in evs if e == "phrase"] == [
        "Администрация работает по будням с 9:00 до 18:30.",
        "Интернет на базе Технопарка входит в пакет.",
    ]


# ---------- характер: ремарки и смех текстом не озвучиваются ----------


def test_stage_directions_and_laughter_are_not_spoken(make_client):
    model = FakeModel(
        say("[happy] *смеётся* Конечно! Кафе — справа от вас. (подмигивает) Ха-ха, проводить вас?")
    )
    client = chat_client(make_client, model)

    evs = events(client, "Где тут кафе?", lang="ru", with_emotion=True)

    assert evs[0] == ("emotion", {"name": "happy"})
    assert [d["text"] for e, d in evs if e == "phrase"] == [
        "Конечно!",
        "Кафе — справа от вас.",
        "Проводить вас?",
    ]
    answer = logged(client)[0].answer
    assert "смеётся" not in answer and "подмигивает" not in answer and "Ха" not in answer


def test_remark_only_reply_is_not_spoken(make_client):
    model = FakeModel(say("[happy] *laughs* [winks]"))
    client = chat_client(make_client, model)

    evs = events(client, "You are funny", lang="en")

    # от ответа ничего не осталось — как молчание модели: готовый отказ, а не «*laughs*»
    assert [d["text"] for e, d in evs if e == "phrase"] == split_phrases(REFUSALS["en"])


# ---------- v1.1: эмоции ----------


def test_emotion_tag_is_cut_and_sent_before_first_phrase(make_client):
    model = FakeModel(say("[happy] Рад помочь! Кафе на первом этаже."))
    client = chat_client(make_client, model)

    evs = events(client, "Где тут кафе?", lang="ru", with_emotion=True)

    assert evs[0] == ("emotion", {"name": "happy"})
    assert [d["text"] for e, d in evs if e == "phrase"] == ["Рад помочь!", "Кафе на первом этаже."]
    assert [e for e, _ in evs].count("emotion") == 1
    assert "[happy]" not in logged(client)[0].answer


def test_no_emotion_tag_is_neutral(make_client):
    model = FakeModel(say("Кафе на первом этаже. [sad] Ещё одна фраза."))
    client = chat_client(make_client, model)

    evs = events(client, "Где тут кафе?", lang="ru", with_emotion=True)

    assert evs[0] == ("emotion", {"name": "neutral"})
    assert all("[sad]" not in d["text"] for e, d in evs if e == "phrase")


def test_emotion_after_action_before_phrase(make_client):
    model = FakeModel(
        call("guide_to", {"place_id": "place:cafe"}), say("[excited] Провожаю вас в кафе.")
    )
    client = chat_client(make_client, model)

    evs = events(client, "Проводи в кафе", lang="ru", with_emotion=True)

    assert [e for e, _ in evs] == ["action", "emotion", "phrase", "done"]
    assert evs[1][1] == {"name": "excited"}


def test_emotion_in_json_mode(make_client):
    model = FakeModel(say("[surprised] Ого, хороший вопрос! Кафе на первом этаже."))
    client = chat_client(make_client, model)

    body = client.post("/v1/chat", json=ask_body("Где кафе?", lang="ru"), headers=AUTH).json()

    assert body["emotion"] == "surprised"
    assert body["phrases"][0] == "Ого, хороший вопрос!"


def test_emotion_on_fallback_and_look(make_client, client):
    evs = events(client, "Какой пароль от Wi-Fi?", lang="ru", with_emotion=True)
    assert evs[0] == ("emotion", {"name": "neutral"})  # CHAT_PROVIDER=off → ответ /ask

    looker = chat_client(make_client, FakeModel(call("look")))
    evs = events(looker, "Что это?", lang="ru", with_emotion=True)
    assert [e for e, _ in evs][:3] == ["action", "emotion", "phrase"]
    assert evs[1][1] == {"name": "thinking"}


# ---------- v1.1: visitor_arrived ----------


def test_visitor_arrived_greeting(make_client):
    model = FakeModel(say("[happy] Здравствуйте! Чем могу помочь?"), say("Кафе на первом этаже."))
    client = chat_client(make_client, model)

    evs = events(client, "", lang="ru", event="visitor_arrived", with_emotion=True)

    assert evs[0] == ("emotion", {"name": "happy"})
    assert [d["text"] for e, d in evs if e == "phrase"] == ["Здравствуйте!", "Чем могу помочь?"]
    assert evs[-1][1]["actions"] == []
    turn = model.requests[0]["messages"][-1]["content"]
    assert "подошёл посетитель" in turn and "ru (русский)" in turn
    assert "<visitor_message>" not in turn
    assert logged(client) == []  # не в журнал вопросов
    assert [m[0] for m in messages(client)] == ["visitor", "assistant"]  # но в память
    # следующий вопрос видит приветствие в истории
    events(client, "Где кафе?", lang="ru")
    convo = model.requests[1]["messages"][1:]
    assert [m["role"] for m in convo] == ["user", "assistant", "user"]
    assert convo[1]["content"] == "Здравствуйте! Чем могу помочь?"


def test_visitor_arrived_ignores_actions(make_client):
    model = FakeModel(call("guide_to", {"place_id": "place:cafe"}), say("Здравствуйте!"))
    client = chat_client(make_client, model)

    evs = events(client, "", lang="en", event="visitor_arrived")

    assert [e for e, _ in evs] == ["phrase", "done"]
    assert evs[-1][1]["actions"] == []


def test_visitor_arrived_without_agent_uses_ready_greeting(client):
    evs = events(client, "", lang="kk", event="visitor_arrived", with_emotion=True)

    assert evs[0] == ("emotion", {"name": "happy"})
    said = " ".join(d["text"] for e, d in evs if e == "phrase")
    assert said in GREETINGS["kk"]
    assert evs[-1][1]["lang"] == "kk" and evs[-1][1]["actions"] == []
    assert logged(client) == []


def test_visitor_arrived_slow_model_uses_ready_greeting(make_client):
    model = FakeModel(say("Поздно."), delay_s=2)
    client = chat_client(make_client, model, agent_greeting_timeout_s=0.3)

    evs = events(client, "", lang="en", event="visitor_arrived")

    assert evs[0][0] == "phrase"
    assert " ".join(d["text"] for e, d in evs if e == "phrase") in GREETINGS["en"]
    assert evs[-1][0] == "done"


def test_empty_question_without_event_is_rejected(client):
    r = client.post("/v1/chat", json=ask_body(""), headers=AUTH)
    assert r.status_code == 400
    r = client.post("/v1/chat", json=ask_body("hi", event="unknown"), headers=AUTH)
    assert r.status_code == 400


def test_chat_turn_is_ask_kind_in_session_history(make_client):
    client = chat_client(make_client, FakeModel(say("Кафе на первом этаже.")))
    events(client, "Где кафе?", lang="ru")
    assert client.app.state.log_writer.wait_idle(5)

    msgs = client.get(f"/v1/sessions/{SESSION}/messages", headers=AUTH).json()["messages"]

    assert [m["kind"] for m in msgs] == ["ask", "ask"]


# ---------- трассировка ----------


class _SpyProcessor(TracingProcessor):
    def __init__(self) -> None:
        self.events: list[str] = []

    def on_trace_start(self, trace) -> None:
        self.events.append("trace_start")

    def on_trace_end(self, trace) -> None:
        self.events.append("trace_end")

    def on_span_start(self, span) -> None:
        self.events.append("span_start")

    def on_span_end(self, span) -> None:
        self.events.append("span_end")

    def shutdown(self) -> None:
        pass

    def force_flush(self) -> None:
        pass


def test_tracing_disabled(make_client, monkeypatch):
    monkeypatch.setenv("OPENAI_AGENTS_DISABLE_TRACING", "false")
    set_tracing_disabled(False)  # даже если кто-то включил — create_app выключает
    model = FakeModel(call("where_is", {"place_id": "place:cafe"}), say("Кафе на первом этаже."))
    client = chat_client(make_client, model)
    provider = get_trace_provider()
    assert provider._disabled is True
    multi = provider._multi_processor
    saved = list(multi._processors)
    spy = _SpyProcessor()
    add_trace_processor(spy)
    try:
        evs = events(client, "Где кафе?", lang="ru")
    finally:
        multi.set_processors(saved)
    assert evs[0][0] == "action"
    assert spy.events == []


# ---------- конфиг ----------


def test_legacy_agent_env_is_compatible():
    s = Settings(
        _env_file=None,
        robot_token="t",
        openrouter_api_key="k",
        agent_model="openrouter/anthropic/claude-haiku-5.5",
        agent_enabled=True,
    )
    assert s.agent_model == "anthropic/claude-haiku-5.5"
    assert s.resolved_chat_provider() == "openrouter"
    off = s.model_copy(update={"agent_enabled": False})
    assert off.resolved_chat_provider() == "off"
    forced = off.model_copy(update={"chat_provider": "openrouter"})
    assert forced.resolved_chat_provider() == "openrouter"


# ---------- eval injection через /v1/chat ----------


class ParrotModel(FakeModel):
    """Худший случай: модель повторяет реплику посетителя дословно (с тегом эмоции)."""

    async def handle(self, request: httpx.Request) -> httpx.Response:
        body = json.loads(request.content)
        self.requests.append(body)
        text = body["messages"][-1]["content"]
        said = text.split("<visitor_message>\n", 1)[1].split("\n</visitor_message>", 1)[0]
        return httpx.Response(
            200, content=_sse(say(f"[happy] {said}")), headers={"content-type": "text/event-stream"}
        )


def test_injection_eval_through_agent_with_parrot_model():
    """Выходной фильтр и лимиты должны удержать каждый случай из eval/injection.yaml."""
    from eval.run_injection import run

    report = run(
        EVAL_DIR / "injection.yaml",
        sleep_s=0,
        force_fake=True,
        endpoint="chat",
        chat_client=ParrotModel().client(),
    )
    assert report["provider"] == "openrouter:anthropic/claude-haiku-5.5"
    assert report["errors"] == 0
    assert report["failed"] == 0, [r for r in report["results"] if not r["ok"]]


def test_injection_eval_chat_mode_fake():
    from eval.run_injection import run

    report = run(EVAL_DIR / "injection.yaml", sleep_s=0, force_fake=True, endpoint="chat")
    assert report["endpoint"] == "chat"
    assert report["failed"] == 0 and report["errors"] == 0


# --- v1.2: шум распознавания ---


def test_noise_is_ignored_without_model_log_or_memory(make_client):
    from tests.test_latency import rows

    model = FakeModel(say("Не должна отвечать."))
    client = chat_client(make_client, model)

    evs = events(client, "Ну... м-м-м (смеётся)", lang="ru", with_emotion=True)

    assert evs == [
        (
            "done",
            {
                "request_id": evs[0][1]["request_id"],
                "lang": "ru",
                "found": False,
                "sources": [],
                "actions": [],
                "ignored": True,
            },
        )
    ]
    assert model.requests == []
    assert logged(client) == []
    assert messages(client) == []
    assert [(r.stage, r.provider) for r in rows(client, "chat")] == [("ignored", "local:noise")]


def test_noise_json_mode(make_client):
    model = FakeModel()
    client = chat_client(make_client, model)

    r = client.post("/v1/chat", json=ask_body("Эээ, ну", lang="ru"), headers=AUTH)

    assert r.status_code == 200
    body = r.json()
    assert body["ignored"] is True
    assert body["answer"] == "" and body["phrases"] == [] and body["actions"] == []
    assert body["found"] is False
    assert model.requests == []


def test_noise_ignored_when_chat_off(client):
    r = client.post("/v1/chat", json=ask_body("(музыка)"), headers=AUTH)
    assert r.status_code == 200
    assert r.json()["ignored"] is True and r.json()["phrases"] == []
    assert logged(client) == []


def test_regular_answer_is_not_ignored(make_client):
    client = chat_client(make_client, FakeModel(say("Кафе на первом этаже.")))
    r = client.post("/v1/chat", json=ask_body("Где кафе?", lang="ru"), headers=AUTH)
    assert r.json()["ignored"] is False


def test_yes_after_robot_question_reaches_model(make_client):
    model = FakeModel(call("guide_to", {"place_id": "place:cafe"}), say("Провожаю."))
    client = chat_client(make_client, model)
    client.post(
        f"/v1/sessions/{SESSION}/messages",
        json={
            "kind": "where_is",
            "question": "Где кафе?",
            "answer": "Кафе на первом этаже. Проводить вас?",
            "lang": "ru",
        },
        headers=AUTH,
    )
    assert client.app.state.log_writer.wait_idle(5)

    evs = events(client, "Да", lang="ru")

    assert evs[-1][1].get("ignored") is not True
    assert model.requests  # «да» — ответ на вопрос робота


def test_yes_without_robot_question_is_noise(make_client):
    model = FakeModel()
    client = chat_client(make_client, model)

    evs = events(client, "Да, я.", lang="ru")

    assert evs[-1][1]["ignored"] is True
    assert model.requests == []
