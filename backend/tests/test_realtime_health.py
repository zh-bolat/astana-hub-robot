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
