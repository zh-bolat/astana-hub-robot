"""Переключатели вкладки «Робот и настройки» (docs/kurultai/robot-settings.md): GET/POST /v1/robot/settings,
хранение в БД, закрытие live-сессий 1012, режим noisy в Realtime-мосту и пайплайне, остаток ElevenLabs."""

# ruff: noqa: F811 — фикстура live_server импортируется из tests/test_live.py и передаётся в тесты параметром

from __future__ import annotations

import asyncio
import logging

import httpx
import pytest
from pydantic import SecretStr

from app.kurultai.voice_credits import VoiceCredits
from tests.conftest import AUTH
from tests.test_chat import FakeModel, say
from tests.test_live import (  # noqa: F401 — live_server: фикстура
    KURULTAI_LIVE_TOKEN,
    FakeRealtime,
    _realtime_server,
    _until,
    live_server,
    run,
    session,
)

K_TOKEN = "kurultai-settings-token"


def _capture(caplog) -> None:
    """create_app настраивает журнал и снимает обработчик caplog — вернуть его после создания приложения."""
    logging.getLogger().addHandler(caplog.handler)


K_AUTH = {"Authorization": f"Bearer {K_TOKEN}"}
LIVE_AUTH = {"Authorization": f"Bearer {KURULTAI_LIVE_TOKEN}"}


@pytest.fixture
def kclient(make_client):
    def _make(**overrides):
        return make_client(kurultai_robot_token=SecretStr(K_TOKEN), **overrides)

    return _make


# ---------- HTTP ----------


def test_settings_require_kurultai_token(kclient):
    c = kclient()
    assert c.get("/v1/robot/settings").status_code == 401
    assert c.get("/v1/robot/settings", headers=AUTH).status_code == 404  # токен Astana Hub
    assert c.post("/v1/robot/settings", json={"noise_mode": "noisy"}).status_code == 401


def test_get_returns_env_defaults(kclient):
    c = kclient(kurultai_live_engine="realtime")
    body = c.get("/v1/robot/settings", headers=K_AUTH).json()
    assert body["engine"]["selected"] == "realtime"
    # ключа OpenAI в тестах нет — следующая сессия пойдёт пайплайном, причина видна во вкладке
    assert body["engine"]["effective"] == "pipeline"
    assert body["engine"]["breaker"] == {"down": False, "reason": "no_openai_key", "retry_in_s": 0}
    assert body["noise_mode"] == "normal"
    assert body["voice"] == {"provider": None, "credits": None, "checked_at": None}
    assert body["server"]["version"] == "0.1.0" and "time" in body["server"]
    assert "quryltai_updated_at" in body


def test_effective_pipeline_when_breaker_is_down(kclient):
    c = kclient(kurultai_live_engine="realtime", kurultai_openai_api_key=SecretStr("sk-test"))
    c.app.state.realtime_tts = lambda text, lang: None  # голос есть
    assert c.get("/v1/robot/settings", headers=K_AUTH).json()["engine"]["effective"] == "realtime"
    c.app.state.realtime_health.mark_down("insufficient_quota")
    engine = c.get("/v1/robot/settings", headers=K_AUTH).json()["engine"]
    assert engine["selected"] == "realtime" and engine["effective"] == "pipeline"
    assert engine["breaker"]["down"] is True
    assert engine["breaker"]["reason"] == "insufficient_quota"
    assert engine["breaker"]["retry_in_s"] > 0


def test_post_rejects_unknown_values(kclient):
    c = kclient()
    for body in (
        {"live_engine": "gpt"},
        {"noise_mode": "loud"},
        {"pin": "1234"},
        {"live_engine": 1},
        {},
        [],
    ):
        r = c.post("/v1/robot/settings", json=body, headers=K_AUTH)
        assert r.status_code == 400, body
    assert c.post("/v1/robot/settings", content=b"x", headers=K_AUTH).status_code == 400
    # неверное значение не записывается, даже если рядом верное
    r = c.post(
        "/v1/robot/settings", json={"noise_mode": "noisy", "live_engine": "x"}, headers=K_AUTH
    )
    assert r.status_code == 400
    assert c.get("/v1/robot/settings", headers=K_AUTH).json()["noise_mode"] == "normal"


def test_post_persists_logs_and_survives_restart(kclient, caplog):
    c = kclient()
    _capture(caplog)
    with caplog.at_level(logging.INFO):
        r = c.post("/v1/robot/settings", json={"noise_mode": "noisy"}, headers=K_AUTH)
    assert r.status_code == 200 and r.json()["noise_mode"] == "noisy"
    rec = next(r for r in caplog.records if r.getMessage() == "robot_settings_changed")
    assert (rec.key, rec.old, rec.new) == ("noise_mode", "normal", "noisy")
    assert rec.client_ip
    # новый процесс api (переустановка APK, рестарт сервера) читает то же из БД
    c2 = kclient()
    body = c2.get("/v1/robot/settings", headers=K_AUTH).json()
    assert body["noise_mode"] == "noisy" and body["engine"]["selected"] == "pipeline"
    assert c2.app.state.kurultai_runtime.as_dict() == {
        "live_engine": "pipeline",
        "noise_mode": "noisy",
    }
    r = c2.post("/v1/robot/settings", json={"live_engine": "realtime"}, headers=K_AUTH)
    assert r.json()["engine"]["selected"] == "realtime"
    assert c.get("/v1/robot/settings", headers=K_AUTH).json()["noise_mode"] == "noisy"


def test_elevenlabs_credits_cached_and_null_on_error(settings):
    calls: list[str] = []
    state = {"fail": False}

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        assert request.headers["xi-api-key"] == "el-key"
        if state["fail"]:
            return httpx.Response(500, json={"detail": "boom"})
        return httpx.Response(200, json={"character_limit": 100_000, "character_count": 85_600})

    s = settings.model_copy(update={"elevenlabs_api_key": SecretStr("el-key")})
    client = httpx.AsyncClient(transport=httpx.MockTransport(handler))

    async def scenario() -> None:
        vc = VoiceCredits(s, client=client)
        assert vc.provider == "elevenlabs"
        credits, checked = await vc.get()
        assert credits == 14_400 and checked is not None
        assert await vc.get() == (credits, checked)  # кэш 5 минут: второго запроса нет
        assert calls == ["/v1/user/subscription"]
        vc2 = VoiceCredits(s, client=client)
        state["fail"] = True
        credits2, checked2 = await vc2.get()
        assert credits2 is None and checked2 is not None
        assert await vc2.get() == (None, checked2)  # ошибка тоже кэшируется (минуту)
        assert len(calls) == 2
        assert await VoiceCredits(settings, client=client).get() == (None, None)  # без ключа
        await client.aclose()

    run(scenario())


def test_voice_block_in_settings_uses_credits(kclient):
    c = kclient(elevenlabs_api_key=SecretStr("el-key"))

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"character_limit": 50_000, "character_count": 10_000})

    c.app.state.kurultai_voice_credits = VoiceCredits(
        c.app.state.settings, client=httpx.AsyncClient(transport=httpx.MockTransport(handler))
    )
    voice = c.get("/v1/robot/settings", headers=K_AUTH).json()["voice"]
    assert voice["provider"] == "elevenlabs" and voice["credits"] == 40_000
    assert voice["checked_at"].endswith("+00:00")


# ---------- live: переключение движка ----------


def _http(url: str) -> str:
    return url.replace("ws://", "http://")


def test_switch_to_pipeline_closes_realtime_session_and_next_uses_pipeline(live_server):
    fake = FakeRealtime()
    url, app, calls, model = _realtime_server(live_server, fake)

    async def scenario() -> None:
        async with httpx.AsyncClient(base_url=_http(url), headers=LIVE_AUTH) as http:
            async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
                await _until(lambda: fake.of("session.update"))
                assert len(calls) == 1
                before = (await http.get("/v1/robot/settings")).json()["engine"]
                assert before == {
                    "selected": "realtime",
                    "effective": "realtime",
                    "breaker": {"down": False, "reason": "", "retry_in_s": 0},
                }
                r = await http.post("/v1/robot/settings", json={"live_engine": "pipeline"})
                assert r.status_code == 200
                assert r.json()["engine"]["selected"] == "pipeline"
                assert r.json()["engine"]["effective"] == "pipeline"
                closed = await c.wait_closed()
                assert closed.rcvd is not None
                assert (closed.rcvd.code, closed.rcvd.reason) == (1012, "settings_changed")
            assert fake.closed
            async with session(url, token=KURULTAI_LIVE_TOKEN) as c:  # робот переподключился
                c.speak(0.6)
                await c.wait("turn_end")
                assert [e["text"] for e in c.events("phrase")] == ["Ответ пайплайна."]
            # обратно на realtime: следующая сессия снова идёт в OpenAI
            r = await http.post("/v1/robot/settings", json={"live_engine": "realtime"})
            assert r.json()["engine"]["selected"] == "realtime"
            fake2 = FakeRealtime()
            app.state.realtime_connector = _connector_for(fake2, calls)
            async with session(url, token=KURULTAI_LIVE_TOKEN):
                await _until(lambda: fake2.of("session.update"))

    run(scenario())
    assert len(calls) == 2  # первая и третья сессии; вторая — пайплайн, OpenAI не трогали
    assert len(model.requests) == 1
    assert app.state.realtime_health.is_down() is False  # переключение — не отказ OpenAI


def _connector_for(fake: FakeRealtime, calls: list[str]):
    async def connector(url, additional_headers=None, max_size=None):
        calls.append(url)
        return fake

    return connector


def test_switch_closes_pipeline_session_too(live_server):
    model = FakeModel(say("Ответ."))
    url, _app, _fakes = live_server(
        kurultai_model=model,
        script=["Акылтай, вопрос"],
        kurultai_robot_token=KURULTAI_LIVE_TOKEN,
        kurultai_web_search_always=False,
    )

    async def scenario() -> None:
        async with httpx.AsyncClient(base_url=_http(url), headers=LIVE_AUTH) as http:
            async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
                r = await http.post("/v1/robot/settings", json={"noise_mode": "noisy"})
                assert r.status_code == 200
                closed = await c.wait_closed()
                assert closed.rcvd is not None and closed.rcvd.code == 1012
            # то же значение ещё раз — ничего не меняется, сессии не трогаем
            async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
                r = await http.post("/v1/robot/settings", json={"noise_mode": "noisy"})
                assert r.status_code == 200
                await asyncio.sleep(0.3)
                assert c.closed is None

    run(scenario())


# ---------- live: режим noisy в Realtime ----------


def _noisy(live_server, fake: FakeRealtime, **overrides):
    return _realtime_server(live_server, fake, kurultai_noise_mode="noisy", **overrides)


def _hello(person_present: bool | None) -> dict:
    hello = {"type": "hello", "lang": "ru", "place_id": "reception", "aec": True}
    if person_present is not None:
        hello["person_present"] = person_present
    return hello


def test_noisy_uses_server_vad_and_logs_mode(live_server, caplog):
    fake = FakeRealtime()
    url, _, _, _ = _noisy(live_server, fake)
    _capture(caplog)

    async def scenario() -> None:
        with caplog.at_level(logging.INFO):
            async with session(url, token=KURULTAI_LIVE_TOKEN):
                await _until(lambda: fake.of("session.update"))

    run(scenario())
    td = fake.of("session.update")[0]["session"]["audio"]["input"]["turn_detection"]
    assert td == {
        "type": "server_vad",
        "threshold": 0.7,
        "prefix_padding_ms": 300,
        "silence_duration_ms": 600,
        "create_response": False,
        "interrupt_response": False,
    }
    opened = next(r for r in caplog.records if r.getMessage() == "live_session_opened")
    assert opened.noise_mode == "noisy" and opened.engine == "realtime"


def test_noisy_ignores_speech_without_person_then_answers_when_present(live_server, caplog):
    fake = FakeRealtime()
    url, _, _, model = _noisy(live_server, fake)
    _capture(caplog)

    async def scenario() -> None:
        with caplog.at_level(logging.INFO):
            async with session(url, token=KURULTAI_LIVE_TOKEN, hello=_hello(False)) as c:
                await _until(lambda: fake.of("session.update"))
                await asyncio.sleep(0.1)  # hello (person_present) дошёл до моста
                fake.utterance("item_1", "Акылтай, сколько комитетов?")
                ignored = await c.wait("ignored")
                assert ignored["reason"] == "no_person"
                assert c.events("user_final")[-1]["text"] == "Акылтай, сколько комитетов?"
                await c.wait("listening", count=2)
                assert fake.creates() == []  # ни «ставки», ни ответа
                assert {d["item_id"] for d in fake.of("conversation.item.delete")} == {"item_1"}
                assert "thinking" not in c.kinds()
                await c.send({"type": "state", "person_present": True})
                await asyncio.sleep(0.05)
                fake.utterance("item_2", "Акылтай, сколько комитетов?")
                end = await c.wait("turn_end")
                assert [e["text"] for e in c.events("phrase")] == [fake.answer]
                await c.send({"type": "playback_done", "turn_id": end["turn_id"]})

    run(scenario())
    assert len(fake.creates()) == 1
    assert model.requests == []
    assert any(r.getMessage() == "live_ignored_no_person" for r in caplog.records)


def test_noisy_without_person_info_answers_as_before(live_server):
    """Старое приложение не шлёт person_present — считаем, что человек есть."""
    fake = FakeRealtime()
    url, _, _, _ = _noisy(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN) as c:
            fake.utterance("item_1", "Акылтай, сколько комитетов?")
            await c.wait("turn_end")

    run(scenario())
    assert len(fake.creates()) == 1


def test_noisy_stop_works_without_person_and_speech_does_not_barge_in(live_server):
    fake = FakeRealtime()
    url, _, _, _ = _noisy(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN, hello=_hello(True)) as c:
            fake.utterance("item_1", "Акылтай, сколько комитетов?")
            await c.wait("turn_end")  # робот ещё говорит (playback_done нет)
            await c.send({"type": "state", "person_present": False})
            await asyncio.sleep(0.05)
            # шумный день: чужая фраза во время ответа не перебивает (stop_only)
            fake.utterance("item_2", "Подожди, а какая погода завтра?")
            await asyncio.sleep(0.3)
            assert "interrupted" not in c.kinds()
            fake.utterance("item_stop", "Стоп")
            await c.wait("interrupted")
            assert {d["item_id"] for d in fake.of("conversation.item.delete")} >= {
                "item_2",
                "item_stop",
            }

    run(scenario())
    assert len(fake.creates()) == 1


def test_noisy_person_leaves_during_transcription_cancels_bet(live_server):
    """Фраза закончилась при человеке (ставка стартовала), расшифровка пришла уже без него — ответ снимается."""
    fake = FakeRealtime()
    url, _, _, _ = _noisy(live_server, fake)

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN, hello=_hello(True)) as c:
            await _until(lambda: fake.of("session.update"))
            fake.push({"type": "input_audio_buffer.speech_started"})
            fake.push({"type": "input_audio_buffer.speech_stopped"})
            fake.push({"type": "input_audio_buffer.committed", "item_id": "item_1"})
            await _until(lambda: len(fake.creates()) == 1)  # ставка начата до расшифровки
            await c.send({"type": "state", "person_present": False})
            await asyncio.sleep(0.05)
            fake.push(
                {
                    "type": "conversation.item.input_audio_transcription.completed",
                    "item_id": "item_1",
                    "transcript": "Акылтай, сколько комитетов?",
                }
            )
            ignored = await c.wait("ignored")
            assert ignored["reason"] == "no_person"
            await asyncio.sleep(0.2)
            assert c.events("phrase") == [] and "turn_start" not in c.kinds()

    run(scenario())
    assert fake.of("response.cancel") or fake.of("conversation.item.delete")


# ---------- live: режим noisy в пайплайне ----------


def test_pipeline_noisy_ignores_speech_without_person(live_server):
    model = FakeModel(say("Восемь комитетов."))
    url, _app, _fakes = live_server(
        kurultai_model=model,
        script=["Акылтай, сколько комитетов?", "Акылтай, сколько комитетов?"],
        segmented_delay=0.2,
        kurultai_robot_token=KURULTAI_LIVE_TOKEN,
        kurultai_web_search_always=False,
        kurultai_noise_mode="noisy",
    )

    async def scenario() -> None:
        async with session(url, token=KURULTAI_LIVE_TOKEN, hello=_hello(False)) as c:
            c.speak(0.6)
            await c.wait("user_final")
            ignored = await c.wait("ignored")
            assert ignored["reason"] == "no_person"
            await c.wait("listening", count=2)
            assert model.requests == []
            await c.send({"type": "state", "person_present": True})
            c.speak(0.6)
            await c.wait("turn_end")
            assert len(model.requests) == 1

    run(scenario())
