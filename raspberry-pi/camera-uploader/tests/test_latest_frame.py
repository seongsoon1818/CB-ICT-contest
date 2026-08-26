import threading
from datetime import datetime, timezone

import pytest

from animalguard_camera.latest_frame import CapturedFrame, LatestFrameSlot


CAPTURED_AT = datetime(2026, 8, 26, 1, 2, 3, tzinfo=timezone.utc)


def frame(sequence: int, jpeg_bytes: bytes | None = None) -> CapturedFrame:
    return CapturedFrame(
        sequence=sequence,
        captured_at=CAPTURED_AT,
        jpeg_bytes=jpeg_bytes or f"jpeg-{sequence}".encode(),
    )


@pytest.mark.parametrize(
    "values",
    [
        {"sequence": 0, "captured_at": CAPTURED_AT, "jpeg_bytes": b"jpeg"},
        {
            "sequence": 1,
            "captured_at": datetime(2026, 8, 26, 1, 2, 3),
            "jpeg_bytes": b"jpeg",
        },
        {"sequence": 1, "captured_at": CAPTURED_AT, "jpeg_bytes": b""},
    ],
)
def test_captured_frame_rejects_invalid_fields(values: dict[str, object]) -> None:
    with pytest.raises(ValueError):
        CapturedFrame(**values)  # type: ignore[arg-type]


def test_unread_frame_is_replaced_by_latest() -> None:
    slot = LatestFrameSlot()

    assert slot.publish(frame(1)) is False
    assert slot.publish(frame(2)) is True

    latest = slot.wait_for_newer(0, threading.Event())
    assert latest is not None
    assert latest.sequence == 2


def test_consumed_frame_is_not_counted_as_overwritten() -> None:
    slot = LatestFrameSlot()
    slot.publish(frame(1))
    assert slot.wait_for_newer(0, threading.Event()) == frame(1)

    assert slot.publish(frame(2)) is False


def test_slot_rejects_non_increasing_sequence() -> None:
    slot = LatestFrameSlot()
    slot.publish(frame(2))

    with pytest.raises(ValueError, match="strictly increase"):
        slot.publish(frame(2))
    with pytest.raises(ValueError, match="strictly increase"):
        slot.publish(frame(1))


def test_same_sequence_is_never_returned_twice() -> None:
    slot = LatestFrameSlot()
    slot.publish(frame(1))
    assert slot.wait_for_newer(0, threading.Event()) == frame(1)
    slot.close()

    assert slot.wait_for_newer(0, threading.Event()) is None


def test_stop_event_avoids_waiting_when_already_set() -> None:
    stop_event = threading.Event()
    stop_event.set()

    assert LatestFrameSlot().wait_for_newer(0, stop_event) is None


def test_close_wakes_waiter_without_a_frame() -> None:
    slot = LatestFrameSlot()
    started = threading.Event()
    finished = threading.Event()
    results: list[CapturedFrame | None] = []

    def wait_for_frame() -> None:
        started.set()
        results.append(slot.wait_for_newer(0, threading.Event()))
        finished.set()

    thread = threading.Thread(target=wait_for_frame)
    thread.start()
    assert started.wait(timeout=1)

    slot.close()

    assert finished.wait(timeout=1)
    thread.join(timeout=1)
    assert results == [None]


def test_request_stop_sets_event_and_wakes_blocked_waiter() -> None:
    slot = LatestFrameSlot()
    stop_event = threading.Event()
    started = threading.Event()
    finished = threading.Event()
    results: list[CapturedFrame | None] = []

    def wait_for_frame() -> None:
        started.set()
        results.append(slot.wait_for_newer(0, stop_event))
        finished.set()

    thread = threading.Thread(target=wait_for_frame)
    thread.start()
    assert started.wait(timeout=1)

    slot.request_stop(stop_event)

    assert stop_event.is_set()
    assert finished.wait(timeout=1)
    thread.join(timeout=1)
    assert results == [None]


def test_publish_after_close_is_rejected() -> None:
    slot = LatestFrameSlot()
    slot.close()

    with pytest.raises(RuntimeError, match="closed"):
        slot.publish(frame(1))
