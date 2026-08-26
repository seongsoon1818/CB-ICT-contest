import logging
import threading
from datetime import datetime, timezone

import pytest

import animalguard_camera.main as main_module
from animalguard_camera.latest_frame import CapturedFrame, LatestFrameSlot
from animalguard_camera.main import (
    ConfigurationRejectedError,
    _raise_recorded_errors,
    log_runtime_snapshot,
    run_capture_producer,
    run_service,
    run_upload_worker,
)
from animalguard_camera.runtime_stats import RuntimeStats
from animalguard_camera.settings import Settings
from animalguard_camera.uploader import UploadResult

CAPTURED_AT = datetime(2026, 8, 26, 1, 2, 3, tzinfo=timezone.utc)


class SequenceSource:
    def __init__(self, values: list[bytes | Exception]) -> None:
        self._values = iter(values)
        self.closed = False

    def capture_jpeg(self) -> bytes:
        value = next(self._values)
        if isinstance(value, Exception):
            raise value
        return value

    def close(self) -> None:
        self.closed = True


class RecordingSlot:
    def __init__(self) -> None:
        self.frames: list[CapturedFrame] = []

    def publish(self, frame: CapturedFrame) -> bool:
        overwritten = bool(self.frames)
        self.frames[:] = [frame]
        return overwritten


class ResultUploader:
    def __init__(
        self,
        result: UploadResult | object,
        *,
        on_upload: object | None = None,
    ) -> None:
        self.result = result
        self.on_upload = on_upload
        self.uploaded: list[bytes] = []
        self.closed = False
        self.in_flight = 0
        self.max_in_flight = 0

    def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
        assert captured_at.tzinfo is not None
        self.in_flight += 1
        self.max_in_flight = max(self.max_in_flight, self.in_flight)
        self.uploaded.append(frame)
        if callable(self.on_upload):
            self.on_upload()
        self.in_flight -= 1
        return self.result  # type: ignore[return-value]

    def close(self) -> None:
        self.closed = True


def make_frame(sequence: int) -> CapturedFrame:
    return CapturedFrame(sequence, CAPTURED_AT, f"frame-{sequence}".encode())


def test_create_source_builds_opencv_from_settings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    created_source = object()
    constructor_calls: list[tuple[str, int, int, float]] = []

    def create_opencv_source(
        device: str,
        width: int,
        height: int,
        target_fps: float,
    ) -> object:
        constructor_calls.append((device, width, height, target_fps))
        return created_source

    monkeypatch.setattr(
        main_module,
        "OpenCvFrameSource",
        create_opencv_source,
    )
    settings = Settings(
        ai_server_base_url="http://127.0.0.1:8000",
        camera_id="cam-001",
        camera_source="opencv",
        camera_device="/dev/video7",
        frame_width=640,
        frame_height=480,
        capture_fps=25.0,
    )

    assert main_module._create_source(settings) is created_source
    assert constructor_calls == [("/dev/video7", 640, 480, 25.0)]


def test_capture_producer_uses_30fps_period_and_publishes_aware_sequences() -> None:
    source = SequenceSource([b"one", b"two"])
    slot = RecordingSlot()
    stats = RuntimeStats(started_monotonic=0.0)
    stop_event = threading.Event()
    waits: list[float] = []
    monotonic_values = iter([0.0, 0.0, 1 / 30])

    def wait(seconds: float) -> bool:
        waits.append(seconds)
        return len(waits) == 2

    run_capture_producer(
        source,
        slot,  # type: ignore[arg-type]
        stats,
        stop_event,
        capture_fps=30,
        wait=wait,
        monotonic=lambda: next(monotonic_values),
        now=lambda: CAPTURED_AT,
    )

    assert [frame.sequence for frame in slot.frames] == [2]
    assert slot.frames[0].captured_at == CAPTURED_AT
    assert waits == pytest.approx([1 / 30, 1 / 30])
    snapshot = stats.snapshot(now_monotonic=1.0)
    assert snapshot.captured == 2
    assert snapshot.overwritten == 1


def test_capture_producer_resets_missed_deadline_without_burst() -> None:
    source = SequenceSource([b"one"])
    waits: list[float] = []
    monotonic_values = iter([0.0, 0.1])

    run_capture_producer(
        source,
        RecordingSlot(),  # type: ignore[arg-type]
        RuntimeStats(started_monotonic=0.0),
        threading.Event(),
        capture_fps=30,
        wait=lambda seconds: waits.append(seconds) is None or True,
        monotonic=lambda: next(monotonic_values),
        now=lambda: CAPTURED_AT,
    )

    assert waits == pytest.approx([1 / 30])


def test_capture_failure_is_counted_and_next_cycle_continues(
    caplog: pytest.LogCaptureFixture,
) -> None:
    source = SequenceSource([OSError("camera"), b"recovered"])
    slot = RecordingSlot()
    stats = RuntimeStats(started_monotonic=0.0)
    waits = 0
    monotonic_values = iter([0.0, 0.0, 1.0, 1.0])

    def wait(seconds: float) -> bool:
        nonlocal waits
        waits += 1
        return waits == 2

    with caplog.at_level(logging.WARNING):
        run_capture_producer(
            source,
            slot,  # type: ignore[arg-type]
            stats,
            threading.Event(),
            capture_fps=1,
            wait=wait,
            monotonic=lambda: next(monotonic_values),
            now=lambda: CAPTURED_AT,
        )

    snapshot = stats.snapshot(now_monotonic=2.0)
    assert snapshot.capture_errors == 1
    assert snapshot.captured == 1
    assert slot.frames[0].jpeg_bytes == b"recovered"
    assert caplog.text.count("Frame capture failed") == 1


def test_upload_worker_sends_one_request_and_skips_intermediate_frames() -> None:
    slot = LatestFrameSlot()
    stats = RuntimeStats(started_monotonic=0.0)
    stop_event = threading.Event()
    first_started = threading.Event()
    allow_first_to_finish = threading.Event()
    second_finished = threading.Event()

    class BlockingUploader(ResultUploader):
        def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
            self.in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self.in_flight)
            self.uploaded.append(frame)
            if len(self.uploaded) == 1:
                first_started.set()
                assert allow_first_to_finish.wait(timeout=1)
            else:
                second_finished.set()
            self.in_flight -= 1
            return UploadResult.SUCCESS

    uploader = BlockingUploader(UploadResult.SUCCESS)
    worker = threading.Thread(
        target=run_upload_worker,
        args=(slot, uploader, stats, stop_event, 0.0),
    )
    worker.start()

    assert slot.publish(make_frame(1)) is False
    assert first_started.wait(timeout=1)
    assert slot.publish(make_frame(2)) is False
    assert slot.publish(make_frame(3)) is True
    stats.record_overwritten()
    allow_first_to_finish.set()
    assert second_finished.wait(timeout=1)
    stop_event.set()
    slot.close()
    worker.join(timeout=1)

    assert uploader.uploaded == [b"frame-1", b"frame-3"]
    assert uploader.max_in_flight == 1
    assert stats.snapshot(now_monotonic=1.0).uploaded == 2


def test_producer_and_worker_integration_keeps_only_latest_while_uploading() -> None:
    source = SequenceSource([b"frame-1", b"frame-2", b"frame-3"])
    slot = LatestFrameSlot()
    stats = RuntimeStats(started_monotonic=0.0)
    stop_event = threading.Event()
    first_started = threading.Event()
    allow_first_to_finish = threading.Event()
    second_finished = threading.Event()

    class SlowUploader(ResultUploader):
        def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
            self.uploaded.append(frame)
            if len(self.uploaded) == 1:
                first_started.set()
                assert allow_first_to_finish.wait(timeout=1)
            else:
                second_finished.set()
                stop_event.set()
                slot.close()
            return UploadResult.SUCCESS

    uploader = SlowUploader(UploadResult.SUCCESS)
    worker = threading.Thread(
        target=run_upload_worker,
        args=(slot, uploader, stats, stop_event, 0.0),
    )
    worker.start()
    waits = 0
    monotonic_values = iter([0.0, 0.0, 1 / 30, 2 / 30])

    def producer_wait(seconds: float) -> bool:
        nonlocal waits
        waits += 1
        if waits == 1:
            assert first_started.wait(timeout=1)
        return waits == 3

    run_capture_producer(
        source,
        slot,
        stats,
        stop_event,
        capture_fps=30,
        wait=producer_wait,
        monotonic=lambda: next(monotonic_values),
        now=lambda: CAPTURED_AT,
    )
    allow_first_to_finish.set()
    assert second_finished.wait(timeout=1)
    worker.join(timeout=1)

    snapshot = stats.snapshot(now_monotonic=1.0)
    assert snapshot.captured == 3
    assert snapshot.uploaded == 2
    assert snapshot.overwritten == 1
    assert uploader.uploaded == [b"frame-1", b"frame-3"]


def test_transient_backoff_resumes_with_latest_frame_without_retry() -> None:
    slot = LatestFrameSlot()
    stop_event = threading.Event()
    stats = RuntimeStats(started_monotonic=0.0)

    class TransientThenSuccessUploader(ResultUploader):
        def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
            self.uploaded.append(frame)
            if len(self.uploaded) == 1:
                return UploadResult.TRANSIENT_ERROR
            stop_event.set()
            slot.close()
            return UploadResult.SUCCESS

    uploader = TransientThenSuccessUploader(UploadResult.SUCCESS)
    slot.publish(make_frame(1))
    waits: list[float] = []

    def backoff(seconds: float) -> bool:
        waits.append(seconds)
        slot.publish(make_frame(2))
        assert slot.publish(make_frame(3)) is True
        stats.record_overwritten()
        return False

    run_upload_worker(
        slot,
        uploader,  # type: ignore[arg-type]
        stats,
        stop_event,
        transient_backoff_seconds=1.0,
        wait=backoff,
    )

    snapshot = stats.snapshot(now_monotonic=1.0)
    assert uploader.uploaded == [b"frame-1", b"frame-3"]
    assert snapshot.uploaded == 1
    assert snapshot.upload_transient_errors == 1
    assert snapshot.overwritten == 1
    assert waits == [1.0]


@pytest.mark.parametrize(
    ("result", "uploaded", "client_errors", "transient_errors"),
    [
        (UploadResult.SUCCESS, 1, 0, 0),
        (UploadResult.CLIENT_ERROR, 0, 1, 0),
        (UploadResult.TRANSIENT_ERROR, 0, 0, 1),
    ],
)
def test_upload_worker_consumes_each_non_configuration_result(
    result: UploadResult,
    uploaded: int,
    client_errors: int,
    transient_errors: int,
) -> None:
    slot = LatestFrameSlot()
    stop_event = threading.Event()
    stats = RuntimeStats(started_monotonic=0.0)

    def stop_after_upload() -> None:
        stop_event.set()
        slot.close()

    uploader = ResultUploader(result, on_upload=stop_after_upload)
    slot.publish(make_frame(1))
    waits: list[float] = []

    run_upload_worker(
        slot,
        uploader,  # type: ignore[arg-type]
        stats,
        stop_event,
        transient_backoff_seconds=1.0,
        wait=lambda seconds: waits.append(seconds) is None or True,
    )

    snapshot = stats.snapshot(now_monotonic=1.0)
    assert snapshot.uploaded == uploaded
    assert snapshot.upload_client_errors == client_errors
    assert snapshot.upload_transient_errors == transient_errors
    assert waits == ([1.0] if result is UploadResult.TRANSIENT_ERROR else [])
    assert uploader.uploaded == [b"frame-1"]


def test_configuration_error_stops_the_whole_service() -> None:
    slot = LatestFrameSlot()
    stop_event = threading.Event()
    stats = RuntimeStats(started_monotonic=0.0)
    uploader = ResultUploader(UploadResult.CONFIGURATION_ERROR)
    slot.publish(make_frame(1))

    with pytest.raises(ConfigurationRejectedError, match="configuration"):
        run_upload_worker(
            slot,
            uploader,  # type: ignore[arg-type]
            stats,
            stop_event,
            0.0,
        )

    assert stop_event.is_set()
    assert slot.wait_for_newer(0, threading.Event()) is None
    assert uploader.uploaded == [b"frame-1"]


def test_unhandled_upload_result_fails_exhaustively() -> None:
    slot = LatestFrameSlot()
    slot.publish(make_frame(1))

    with pytest.raises(AssertionError, match="Unhandled UploadResult"):
        run_upload_worker(
            slot,
            ResultUploader(object()),  # type: ignore[arg-type]
            RuntimeStats(started_monotonic=0.0),
            threading.Event(),
            0.0,
        )


def test_runtime_snapshot_log_contains_only_aggregate_fields(
    caplog: pytest.LogCaptureFixture,
) -> None:
    stats = RuntimeStats(started_monotonic=0.0)
    stats.record_captured(captured_monotonic=0.5)
    stats.record_uploaded()

    with caplog.at_level(logging.INFO):
        log_runtime_snapshot(stats.snapshot(now_monotonic=1.0))

    assert "captured=1" in caplog.text
    assert "uploaded=1" in caplog.text
    assert "effectiveCaptureFps=1.000" in caplog.text
    assert "latestFrameAgeMs=500.0" in caplog.text
    assert "jpeg" not in caplog.text.lower()


def test_service_raises_and_closes_resources_after_configuration_stop() -> None:
    source = SequenceSource([b"frame"])
    uploader = ResultUploader(UploadResult.CONFIGURATION_ERROR)

    with pytest.raises(ConfigurationRejectedError, match="configuration"):
        run_service(
            Settings("http://ai.example", "cam-001", capture_fps=30),
            source=source,
            uploader=uploader,  # type: ignore[arg-type]
        )

    assert source.closed
    assert uploader.closed
    assert uploader.uploaded == [b"frame"]


def test_main_reports_runtime_failure_and_returns_one(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    monkeypatch.setattr(main_module.signal, "signal", lambda *args: None)
    monkeypatch.setattr(
        main_module.Settings,
        "from_env",
        lambda: Settings("http://ai.example", "cam-001"),
    )

    def fail_during_runtime(*args: object, **kwargs: object) -> None:
        raise RuntimeError("connection pool exhausted")

    monkeypatch.setattr(main_module, "run_service", fail_during_runtime)

    with caplog.at_level(logging.ERROR):
        assert main_module.main() == 1

    assert "Camera uploader stopped after an error" in caplog.text
    assert "failed to start" not in caplog.text


def test_main_reports_settings_failure_as_startup_failure(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    monkeypatch.setattr(main_module.signal, "signal", lambda *args: None)

    def reject_settings() -> Settings:
        raise ValueError("CAMERA_ID is required")

    monkeypatch.setattr(main_module.Settings, "from_env", reject_settings)

    with caplog.at_level(logging.ERROR):
        assert main_module.main() == 1

    assert "Camera uploader failed to start" in caplog.text


def test_secondary_worker_errors_are_logged_before_first_error_is_raised(
    caplog: pytest.LogCaptureFixture,
) -> None:
    errors = [ValueError("first failure"), RuntimeError("second failure")]

    with caplog.at_level(logging.ERROR), pytest.raises(
        ValueError,
        match="first failure",
    ):
        _raise_recorded_errors(errors)

    assert "Additional camera uploader worker error" in caplog.text
    assert "RuntimeError" in caplog.text
    assert "second failure" in caplog.text


def test_unexpected_upload_error_escapes_after_resources_are_closed() -> None:
    source = SequenceSource([b"frame"])

    class ExplodingUploader(ResultUploader):
        def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
            raise ValueError("unexpected upload bug")

    uploader = ExplodingUploader(UploadResult.SUCCESS)

    with pytest.raises(ValueError, match="unexpected upload bug"):
        run_service(
            Settings("http://ai.example", "cam-001", capture_fps=30),
            source=source,
            uploader=uploader,  # type: ignore[arg-type]
        )

    assert source.closed
    assert uploader.closed


def test_service_does_not_close_resources_owned_by_live_workers() -> None:
    stop_event = threading.Event()
    release_workers = threading.Event()
    source_blocked = threading.Event()
    upload_started = threading.Event()
    source_released = threading.Event()
    upload_released = threading.Event()

    class BlockingSource(SequenceSource):
        def __init__(self) -> None:
            super().__init__([])
            self.capture_count = 0
            self.worker_was_daemon = False

        def capture_jpeg(self) -> bytes:
            self.capture_count += 1
            self.worker_was_daemon = threading.current_thread().daemon
            if self.capture_count == 1:
                return b"frame"
            source_blocked.set()
            assert release_workers.wait(timeout=5)
            source_released.set()
            return b"late-frame"

    class BlockingUploader(ResultUploader):
        def __init__(self) -> None:
            super().__init__(UploadResult.SUCCESS)
            self.worker_was_daemon = False

        def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
            self.worker_was_daemon = threading.current_thread().daemon
            upload_started.set()
            assert release_workers.wait(timeout=5)
            upload_released.set()
            return UploadResult.SUCCESS

    source = BlockingSource()
    uploader = BlockingUploader()

    def request_stop_when_workers_are_blocked() -> None:
        assert source_blocked.wait(timeout=1)
        assert upload_started.wait(timeout=1)
        stop_event.set()

    stopper = threading.Thread(target=request_stop_when_workers_are_blocked)
    stopper.start()
    try:
        with pytest.raises(RuntimeError, match="did not stop in time"):
            run_service(
                Settings(
                    "http://ai.example",
                    "cam-001",
                    capture_fps=30,
                    http_timeout_seconds=0.01,
                    upload_transient_backoff_seconds=0.0,
                ),
                source=source,
                uploader=uploader,  # type: ignore[arg-type]
                stop_event=stop_event,
            )

        assert source.worker_was_daemon
        assert uploader.worker_was_daemon
        assert not source.closed
        assert not uploader.closed
    finally:
        release_workers.set()
        assert source_released.wait(timeout=1)
        assert upload_released.wait(timeout=1)
        stopper.join(timeout=1)
