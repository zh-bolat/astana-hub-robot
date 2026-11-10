"""Навигация Ақылтая в Realtime-движке (docs/kurultai/navigation.md): guide_to после playback_done, поездка,
итог поездки голосом сервера, политика предложений, точки карты.

Сервер и подделка OpenAI Realtime — из tests/test_live.py (FakeRealtime, _realtime_server, session).
"""

from __future__ import annotations

# ruff: noqa: F811 — фикстура live_server импортируется из test_live и приходит аргументом в тесты
import asyncio
import json
from pathlib import Path

from app.kurultai.offers import OfferPolicy
from app.kurultai.places import load_places
from app.kurultai.realtime.instructions import GUIDE_TOOL
from tests.test_live import (  # noqa: F401 — live_server: фикстура из test_live
    KURULTAI_LIVE_TOKEN,
    FakeRealtime,
    _realtime_server,
    _until,
    live_server,
    run,
    session,
)

PLACES = Path(__file__).resolve().parents[1] / "knowledge" / "kurultai_places.yaml"


# ---------- точки карты ----------
def test_places_yaml_matches_contract():
    places = load_places(PLACES)
    assert places.ids() == ["museum", "restroom", "wardrobe", "lobby", "invitation_room"]
    museum = places.find("museum")
    assert museum is not None and museum.map_name == "MuseumKurultai"
    assert museum.name("ru") == "Музей Курултая" and museum.name("kk") == "Құрылтай мұражайы"
    assert places.find("ДӘРЕТХАНА") is not None and places.find("Wardrobe").id == "wardrobe"
    assert places.find("к выходу").id == "lobby" and places.find("Пункт приема", targets_only=False)
    # пост робота — служебная точка: целью не бывает, в списке для модели его нет
    home = places.find("home", targets_only=False)
    assert home is not None and home.service and home.map_name == "Пункт приема"
    assert places.find("home") is None and places.find("пункт приёма") is None
    assert "home" not in places.prompt_block(
        "ru"
    ) and "museum: Музей Курултая" in places.prompt_block("ru")
    assert places.find("кафе") is None and places.find("") is None


def test_places_available_filters_by_robot_map():
    places = load_places(PLACES)
    assert places.available(None) is places
    few = places.available(["MuseumKurultai", "restroom", "Пункт приема"])
    assert few.ids() == ["museum", "restroom"]
    assert few.find("гардероб") is None and few.find("музей").id == "museum"
    assert not places.available([]) and places.available([]).prompt_block("ru") == ""


def test_places_missing_file_is_empty(tmp_path):
    assert not load_places(tmp_path / "nope.yaml")
    (tmp_path / "bad.yaml").write_text("places: [{id: x}]")
    assert not load_places(tmp_path / "bad.yaml")


def test_offer_policy_interval():
    t = [0.0]
    policy = OfferPolicy(clock=lambda: t[0])
    assert policy.due(600)
    policy.mark()
    assert not policy.due(600)
    t[0] = 599.0
    assert not policy.due(600)
    t[0] = 600.0
    assert policy.due(600) and policy.count == 1


# ---------- guide_to ----------
def _guide_fake(place_id: str, phrase: str = "[happy] Пойдёмте, провожу вас.") -> FakeRealtime:
    """1-й ответ: фраза + вызов guide_to; 2-й (после результата инструмента): молча."""
    fake = FakeRealtime()

    def respond(n: int) -> None:
        fake.push({"type": "response.created", "response": {"id": fake.cur}})
        if n == 1:
            fake.say(phrase)
            fake.push(
                {
                    "type": "response.done",
                    "response": {
                        "id": fake.cur,
                        "status": "completed",
                        "output": [
                            {
                                "type": "function_call",
                                "name": "guide_to",
                                "call_id": "g1",
                                "arguments": json.dumps({"place_id": place_id}),
                            }
                        ],
                    },
                }
            )
            return
        fake.push({"type": "response.output_text.done"})
        fake.push(
            {
                "type": "response.done",
                "response": {
                    "id": fake.cur,
                    "status": "completed",
                    "output": [{"type": "message"}],
                },
            }
        )

    fake._respond = respond
    return fake


def _tool_output(fake: FakeRealtime) -> dict:
    items = [m["item"] for m in fake.of("conversation.item.create")]
    assert items and items[0]["type"] == "function_call_output"
    return json.loads(items[0]["output"])


def test_guide_to_action_after_playback_done(live_server):
    fake = _guide_fake("museum")
    url, _, _, _ = _realtime_server(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            cfg = fake.of("session.update")[0]["session"]
            assert GUIDE_TOOL in cfg["tools"]
            assert "museum: Музей Курултая" in cfg["instructions"]
            assert "guide_to" in cfg["instructions"]
            fake.utterance("item_1", "Акылтай, проводи меня в музей")
            end = await c.wait("turn_end")
            assert [e["text"] for e in c.events("phrase")] == ["Пойдёмте, провожу вас."]
            await asyncio.sleep(0.3)
            assert c.events("action") == []  # робот ещё говорит — не едем
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            action = await c.wait("action")
            assert action == {
                "type": "action",
                "type_": "guide_to",
                "place_id": "museum",
                "map_name": "MuseumKurultai",
                "turn_id": end["turn_id"],
            }

    run(scenario())
    out = _tool_output(fake)
    assert out == {"ok": True, "place": "Музей Курултая"}
    assert len(fake.creates()) == 2


def test_guide_to_without_playback_done_fires_by_timer(live_server):
    fake = _guide_fake("restroom")
    url, _, _, _ = _realtime_server(live_server, fake, kurultai_guide_playback_timeout_s=0.4)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            fake.utterance("item_1", "Акылтай, где туалет?")
            end = await c.wait("turn_end")
            assert c.events("action") == []
            action = await c.wait("action", timeout=3)
            assert action["type_"] == "guide_to" and action["map_name"] == "restroom"
            assert action["turn_id"] == end["turn_id"]

    run(scenario())


def test_guide_to_unknown_or_service_place_is_refused(live_server):
    fake = _guide_fake("home")
    url, _, _, _ = _realtime_server(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            fake.utterance("item_1", "Акылтай, проводи на пункт приёма")
            end = await c.wait("turn_end")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.wait("listening", count=2)
            await asyncio.sleep(0.3)
            assert c.events("action") == []

    run(scenario())
    out = _tool_output(fake)
    assert out["ok"] is False and out["error"] == "unknown place"
    assert out["places"] == ["museum", "restroom", "wardrobe", "lobby", "invitation_room"]


def test_guide_to_only_places_from_robot_hello(live_server):
    fake = _guide_fake("wardrobe")
    url, _, _, _ = _realtime_server(live_server, fake)
    hello = {"type": "hello", "lang": "ru", "aec": True, "places": ["MuseumKurultai", "restroom"]}

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN, hello=hello) as c:
            await _until(lambda: len(fake.of("session.update")) >= 2)
            cfg = fake.of("session.update")[-1]["session"]
            assert "wardrobe" not in cfg["instructions"] and "museum: " in cfg["instructions"]
            fake.utterance("item_1", "Акылтай, проводи в гардероб")
            await c.wait("turn_end")

    run(scenario())
    out = _tool_output(fake)
    assert out["ok"] is False and out["places"] == ["museum", "restroom"]


def test_guide_to_is_dropped_when_interrupted(live_server):
    fake = _guide_fake("museum")
    url, _, _, _ = _realtime_server(live_server, fake, kurultai_guide_playback_timeout_s=0.3)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            fake.utterance("item_1", "Акылтай, проводи меня в музей")
            await c.wait("turn_end")
            await c.send({"type": "interrupt"})  # кнопка «Стоп», пока робот говорит «Пойдёмте»
            await c.wait("interrupted")
            await asyncio.sleep(0.6)
            assert c.events("action") == []

    run(scenario())


# ---------- поездка ----------
def test_navigation_ignores_speech_stops_on_command_and_speaks_result(live_server):
    fake = FakeRealtime(answer="[happy] Мы на месте, это Музей Курултая.")
    url, _, _, _ = _realtime_server(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            await c.send({"type": "state", "navigating": True, "place_id": "museum"})
            await asyncio.sleep(0.1)
            fake.utterance("item_1", "Акылтай, сколько комитетов?")
            ignored = await c.wait("ignored")
            assert ignored["reason"] == "navigating"
            assert c.events("user_final")[0]["text"] == "Акылтай, сколько комитетов?"
            assert fake.creates() == []  # ответа нет
            assert fake.of("conversation.item.delete")[0]["item_id"] == "item_1"
            fake.utterance("item_2", "Стоп")
            action = await c.wait("action")
            assert action == {"type": "action", "type_": "stop_navigation"}
            assert c.events("phrase") == []
            await c.send(
                {"type": "state", "navigating": False, "place_id": "museum", "result": "arrived"}
            )
            end = await c.wait("turn_end")
            assert [e["text"] for e in c.events("phrase")] == ["Мы на месте, это Музей Курултая."]
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            fake.utterance("item_3", "А что там есть?")  # окно разговора открыто — без имени
            await c.wait("turn_end", count=2)

    run(scenario())
    first = fake.creates()[0]["response"]["instructions"]
    assert "Ақылтай" in first  # полные инструкции сессии + дополнение
    assert "привёз посетителя к месту «Музей Курултая»" in first
    assert len(fake.creates()) == 2


def test_navigation_failed_and_stopped_phrases(live_server):
    fake = FakeRealtime(answer="[sad] Не получилось проехать.")
    url, _, _, _ = _realtime_server(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            await c.send({"type": "state", "navigating": True, "place_id": "restroom"})
            await c.send(
                {
                    "type": "state",
                    "navigating": False,
                    "place_id": "restroom",
                    "result": "failed",
                    "reason": "no_route",
                }
            )
            end = await c.wait("turn_end")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.send({"type": "state", "navigating": True, "place_id": "lobby"})
            await c.send({"type": "interrupt"})  # тап по баннеру в поездке
            action = await c.wait("action")
            assert action["type_"] == "stop_navigation"
            await c.send(
                {"type": "state", "navigating": False, "place_id": "lobby", "result": "stopped"}
            )
            await c.wait("turn_end", count=2)

    run(scenario())
    texts = [m["response"]["instructions"] for m in fake.creates()]
    assert "«Туалет»: маршрут не найден" in texts[0]
    assert "поездку к месту «Холл» остановили" in texts[1]


def test_auto_return_home_is_silent(live_server):
    fake = FakeRealtime()
    url, _, _, _ = _realtime_server(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            await c.send({"type": "state", "navigating": True, "place_id": "home", "auto": True})
            await asyncio.sleep(0.1)
            fake.utterance("item_1", "Акылтай, привет")
            assert (await c.wait("ignored"))["reason"] == "navigating"
            await c.send(
                {
                    "type": "state",
                    "navigating": False,
                    "place_id": "home",
                    "result": "arrived",
                    "auto": True,
                }
            )
            await asyncio.sleep(0.4)
            assert c.events("phrase") == [] and "turn_start" not in c.kinds()
            fake.utterance("item_2", "Акылтай, сколько комитетов?")  # после возврата — как обычно
            await c.wait("turn_end")

    run(scenario())
    assert len(fake.creates()) == 1 and "response" not in fake.creates()[0]


# ---------- предложение проводить ----------
def _offered(create: dict) -> bool:
    text = (create.get("response") or {}).get("instructions") or ""
    return "предложи проводить" in text


def _connector(fake: FakeRealtime):
    """Вторая сессия робота на том же сервере (та же OfferPolicy) — своя подделка OpenAI."""

    async def connector(url, additional_headers=None, max_size=None):
        return fake

    return connector


def test_offer_in_greeting_once_per_interval(live_server):
    fake = FakeRealtime(answer="[happy] Здравствуйте! Хотите, провожу вас в музей Курултая?")
    url, app, _, _ = _realtime_server(live_server, fake, kurultai_live_greeting=True)
    fake2 = FakeRealtime()

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            await c.send({"type": "visitor_arrived"})
            end = await c.wait("turn_end")
            assert c.events("phrase")[0]["text"].startswith("Здравствуйте")
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
        app.state.realtime_connector = _connector(fake2)
        async with session(url, session_id="second", token=KURULTAI_LIVE_TOKEN) as c2:
            await c2.send({"type": "visitor_arrived"})
            await c2.wait("turn_end")

    run(scenario())
    first = fake.creates()[0]["response"]["instructions"]
    assert "подошёл посетитель" in first
    assert "Ақылтай" in first and "Язык ответа: ru" in first  # полные инструкции + дополнение
    assert "предложи проводить посетителя в «Музей Курултая»" in first
    assert "Хотите, провожу вас в Музей Курултая?" in first
    second = fake2.creates()[0]
    assert "подошёл посетитель" in second["response"]["instructions"]
    assert not _offered(second)  # вторая сессия в пределах 10 минут — без предложения


def test_offer_in_second_answer_when_no_greeting(live_server):
    fake = FakeRealtime()
    url, _, _, _ = _realtime_server(live_server, fake, kurultai_wake_word=False)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            for n in range(1, 4):
                fake.utterance(f"item_{n}", f"Вопрос номер {n} про Курултай")
                end = await c.wait("turn_end", count=n)
                await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
                await c.wait("listening", count=n + 1)

    run(scenario())
    creates = fake.creates()
    assert [_offered(m) for m in creates] == [False, True, False]  # одно предложение на сессию


def test_offer_in_kazakh_and_not_after_trip(live_server):
    fake = FakeRealtime()
    url, app, _, _ = _realtime_server(live_server, fake, kurultai_live_greeting=True)
    fake2 = FakeRealtime()
    hello = {"type": "hello", "lang": "kk", "aec": True, "places": ["MuseumKurultai"]}

    async def scenario() -> None:
        async with session(url, lang="kk", token=KURULTAI_LIVE_TOKEN, hello=hello) as c:
            await c.send({"type": "state", "navigating": True, "place_id": "museum"})
            await c.send(
                {"type": "state", "navigating": False, "place_id": "museum", "result": "arrived"}
            )
            end = await c.wait("turn_end")  # фраза прибытия
            await c.send({"type": "playback_done", "turn_id": end["turn_id"]})
            await c.send({"type": "visitor_arrived"})
            await c.wait("turn_end", count=2)
        app.state.realtime_connector = _connector(fake2)
        async with session(
            url, session_id="kk2", lang="kk", token=KURULTAI_LIVE_TOKEN, hello=hello
        ) as c:
            await c.send({"type": "visitor_arrived"})
            await c.wait("turn_end")

    run(scenario())
    assert not _offered(fake.creates()[1])  # в этой сессии уже была поездка
    kk = fake2.creates()[0]
    assert _offered(kk) and "Құрылтай мұражайы" in kk["response"]["instructions"]


def test_no_offer_when_place_missing_on_robot_or_disabled(live_server):
    fake = FakeRealtime()
    url, _, _, _ = _realtime_server(live_server, fake, kurultai_live_greeting=True)
    hello = {"type": "hello", "lang": "ru", "aec": True, "places": ["restroom", "Lobby"]}

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN, hello=hello) as c:
            await c.send({"type": "visitor_arrived"})
            await c.wait("turn_end")

    run(scenario())
    assert fake.creates() and not _offered(fake.creates()[0])

    fake2 = FakeRealtime()
    url2, _, _, _ = _realtime_server(
        live_server, fake2, kurultai_live_greeting=True, kurultai_offer_place=""
    )

    async def scenario2() -> None:
        async with session(url2, token=KURULTAI_LIVE_TOKEN) as c:
            await c.send({"type": "visitor_arrived"})
            await c.wait("turn_end")

    run(scenario2())
    assert fake2.creates() and not _offered(fake2.creates()[0])
