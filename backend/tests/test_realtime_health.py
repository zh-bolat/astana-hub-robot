"""Предохранитель Realtime (app/kurultai/realtime/health.py)."""

from app.kurultai.realtime.health import FATAL_CODES, RealtimeHealth


class Clock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


def test_down_grows_and_resets():
    clock = Clock()
    h = RealtimeHealth(retry_s=120, max_retry_s=600, clock=clock)
    assert not h.is_down()
    h.mark_down("credit_balance_exhausted")
    assert h.is_down() and h.seconds_left() == 120 and h.reason == "credit_balance_exhausted"
    clock.t += 121
    assert not h.is_down()
    h.mark_down("insufficient_quota")  # повтор — пауза вдвое длиннее
    assert h.seconds_left() == 240
    h.mark_down("x")
    h.mark_down("x")
    assert h.seconds_left() == 600  # не больше max
    h.mark_up()
    assert not h.is_down() and h.reason == ""
    h.mark_down("y")
    assert h.seconds_left() == 120  # штраф сброшен


def test_losses_in_a_row_trip_breaker():
    clock = Clock()
    h = RealtimeHealth(clock=clock)
    assert not h.note_loss() and not h.note_loss()
    clock.t += 70  # старые обрывы забываются
    assert not h.note_loss() and not h.note_loss()
    assert h.note_loss() and h.is_down() and h.reason == "reconnect_loop"


def test_fatal_codes():
    assert {"credit_balance_exhausted", "insufficient_quota", "invalid_api_key"} <= FATAL_CODES
    assert "rate_limit_exceeded" not in FATAL_CODES


def test_first_message_works_with_async_generator_connections():
    """websockets.ClientConnection: __aiter__ — асинхронный генератор без __anext__, читать надо через recv()."""
    import asyncio

    from app.kurultai.realtime.bridge import first_message

    class WsLike:
        async def recv(self):
            return '{"type": "session.created"}'

        async def __aiter__(self):
            yield '{"type": "never"}'

    class FakeLike:
        def __aiter__(self):
            return self

        async def __anext__(self):
            return '{"type": "session.created"}'

    assert asyncio.run(first_message(WsLike())) == '{"type": "session.created"}'
    assert asyncio.run(first_message(FakeLike())) == '{"type": "session.created"}'


def test_background_probe_keeps_realtime_off_until_openai_answers():
    """Пока OpenAI лежит, is_down() держится (сессии робота идут старой цепочкой без ожидания), а фоновая
    проверка сама возвращает Realtime, когда OpenAI ответил."""
    import asyncio

    answers: list[str | None] = ["credit_balance_exhausted", None]
    calls: list[int] = []

    async def prober() -> str | None:
        calls.append(1)
        return answers[len(calls) - 1]

    async def scenario() -> None:
        h = RealtimeHealth()
        h.retry_s = h.max_retry_s = h._penalty = 0.05  # в тесте — быстрые паузы
        h.prober = prober
        h.mark_down("credit_balance_exhausted")
        assert h.is_down()
        await asyncio.sleep(0.09)  # первая проверка: денег всё ещё нет
        assert h.is_down() and calls == [1] and h.reason == "credit_balance_exhausted"
        for _ in range(100):
            if not h.is_down():
                break
            await asyncio.sleep(0.02)
        assert not h.is_down() and len(calls) == 2 and h.reason == ""
        await h.stop()

    asyncio.run(scenario())
