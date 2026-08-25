import threading
from datetime import datetime, timezone

import httpx
import pytest

from animalguard_camera.main import run_capture_loop, run_service
from animalguard_camera.settings import Settings
from animalguard_camera.uploader import FrameUploader


class RecordingSource:
    def __init__(self, events: list[object], frames: list[bytes]) -> None:
        self.events = events
        self.frames = iter(frames)
        self.closed = False

    def capture_jpeg(self) -> bytes:
        frame = next(self.frames)
        self.events.append(("capture", frame))
        return frame

    def close(self) -> None:
        self.closed = True


class RecordingUploader:
    def __init__(self, events: list[object]) -> None:
        self.events = events
        self.closed = False
        self.in_flight = 0
        self.max_in_flight = 0

    def upload(self, frame: bytes, captured_at: datetime) -> None:
        self.in_flight += 1
        self.max_in_flight = max(self.max_in_flight, self.in_flight)
        self.events.append(("upload", frame, captured_at))
        self.in_flight -= 1

    def close(self) -> None:
        self.closed = True


class ExplodingUploader(RecordingUploader):
    def upload(self, frame: bytes, captured_at: datetime) -> None:
        raise ValueError("unexpected upload bug")


def test_loop_waits_after_each_completed_upload_and_captures_a_new_frame() -> None:
    events: list[object] = []
    source = RecordingSource(events, [b"failed-frame", b"new-frame"])
    uploader = RecordingUploader(events)
    stop_event = threading.Event()
    monotonic_values = iter([0.0, 0.2, 1.0, 1.4])
    waits: list[float] = []

    def wait(seconds: float) -> bool:
        waits.append(seconds)
        return len(waits) == 2

    run_capture_loop(
        source,
        uploader,  # type: ignore[arg-type]
        interval_seconds=1.0,
        stop_event=stop_event,
        wait=wait,
        monotonic=lambda: next(monotonic_values),
        now=lambda: datetime(2026, 8, 25, tzinfo=timezone.utc),
    )

    assert [event[1] for event in events if event[0] == "capture"] == [
        b"failed-frame",
        b"new-frame",
    ]
    assert [event[1] for event in events if event[0] == "upload"] == [
        b"failed-frame",
        b"new-frame",
    ]
    assert [event[0] for event in events] == ["capture", "upload", "capture", "upload"]
    assert waits == pytest.approx([0.8, 0.6])
    assert uploader.max_in_flight == 1


def test_service_closes_source_and_uploader_on_shutdown() -> None:
    events: list[object] = []
    source = RecordingSource(events, [b"frame"])
    uploader = RecordingUploader(events)
    stop_event = threading.Event()

    run_service(
        Settings("http://ai.example", "cam-001"),
        source=source,
        uploader=uploader,  # type: ignore[arg-type]
        stop_event=stop_event,
        wait=lambda seconds: True,
    )

    assert source.closed
    assert uploader.closed


def test_unexpected_upload_error_escapes_after_resources_are_closed() -> None:
    events: list[object] = []
    source = RecordingSource(events, [b"frame"])
    uploader = ExplodingUploader(events)

    with pytest.raises(ValueError, match="unexpected upload bug"):
        run_service(
            Settings("http://ai.example", "cam-001"),
            source=source,
            uploader=uploader,  # type: ignore[arg-type]
            wait=lambda seconds: True,
        )

    assert source.closed
    assert uploader.closed


@pytest.mark.parametrize(
    "first_result",
    [400, 422, httpx.ReadTimeout("slow"), httpx.ConnectError("offline")],
)
def test_loop_continues_with_a_new_frame_after_upload_failure(
    first_result: int | httpx.RequestError,
) -> None:
    requests: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request.read())
        if len(requests) == 1:
            if isinstance(first_result, int):
                return httpx.Response(first_result, json={"detail": "failed"})
            first_result.request = request
            raise first_result
        return httpx.Response(200, json={"riskLevel": "LOW"})

    source = RecordingSource([], [b"discarded-frame", b"fresh-frame"])
    client = httpx.Client(transport=httpx.MockTransport(handler))
    uploader = FrameUploader("http://ai.example", "cam-001", 10, client=client)
    wait_calls = 0

    def wait(seconds: float) -> bool:
        nonlocal wait_calls
        wait_calls += 1
        return wait_calls == 2

    run_capture_loop(
        source,
        uploader,
        interval_seconds=1,
        stop_event=threading.Event(),
        wait=wait,
    )

    assert len(requests) == 2
    assert b"discarded-frame" in requests[0]
    assert b"fresh-frame" not in requests[0]
    assert b"fresh-frame" in requests[1]
    assert b"discarded-frame" not in requests[1]
