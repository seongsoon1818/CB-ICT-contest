import logging
import signal
import threading
import time
from collections.abc import Callable
from datetime import datetime, timezone

from animalguard_camera.frame_source import (
    FileFrameSource,
    FrameSource,
    Picamera2FrameSource,
)
from animalguard_camera.latest_frame import CapturedFrame, LatestFrameSlot
from animalguard_camera.runtime_stats import RuntimeSnapshot, RuntimeStats
from animalguard_camera.settings import Settings
from animalguard_camera.uploader import FrameUploader, UploadResult


LOGGER = logging.getLogger(__name__)
CAPTURE_WARNING_INTERVAL_SECONDS = 10.0


def run_capture_producer(
    source: FrameSource,
    slot: LatestFrameSlot,
    stats: RuntimeStats,
    stop_event: threading.Event,
    capture_fps: float,
    *,
    wait: Callable[[float], bool] | None = None,
    monotonic: Callable[[], float] = time.monotonic,
    now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
) -> None:
    wait_for_stop = wait or stop_event.wait
    capture_period = 1.0 / capture_fps
    next_deadline = monotonic()
    next_warning_at = next_deadline
    sequence = 0

    while not stop_event.is_set():
        try:
            jpeg_bytes = source.capture_jpeg()
            captured_at = now()
            capture_completed = monotonic()
            captured_frame = CapturedFrame(
                sequence=sequence + 1,
                captured_at=captured_at,
                jpeg_bytes=jpeg_bytes,
            )
        except Exception as error:
            capture_completed = monotonic()
            stats.record_capture_error()
            if capture_completed >= next_warning_at:
                LOGGER.warning(
                    "Frame capture failed; continuing with the next cycle (%s)",
                    type(error).__name__,
                )
                next_warning_at = (
                    capture_completed + CAPTURE_WARNING_INTERVAL_SECONDS
                )
        else:
            try:
                overwritten = slot.publish(captured_frame)
            except RuntimeError:
                if stop_event.is_set():
                    break
                raise
            sequence = captured_frame.sequence
            stats.record_captured(capture_completed)
            if overwritten:
                stats.record_overwritten()

        next_deadline += capture_period
        if next_deadline <= capture_completed:
            next_deadline = capture_completed + capture_period
        if wait_for_stop(next_deadline - capture_completed):
            break


def run_upload_worker(
    slot: LatestFrameSlot,
    uploader: FrameUploader,
    stats: RuntimeStats,
    stop_event: threading.Event,
    transient_backoff_seconds: float,
    *,
    wait: Callable[[float], bool] | None = None,
) -> None:
    wait_for_stop = wait or stop_event.wait
    last_sequence = 0

    while not stop_event.is_set():
        frame = slot.wait_for_newer(last_sequence, stop_event)
        if frame is None:
            break

        last_sequence = frame.sequence
        result = uploader.upload(frame.jpeg_bytes, frame.captured_at)
        match result:
            case UploadResult.SUCCESS:
                stats.record_uploaded()
            case UploadResult.CLIENT_ERROR:
                stats.record_upload_client_error()
            case UploadResult.CONFIGURATION_ERROR:
                LOGGER.error(
                    "Stopping camera uploader because its configuration was rejected"
                )
                slot.request_stop(stop_event)
                break
            case UploadResult.TRANSIENT_ERROR:
                stats.record_upload_transient_error()
                if wait_for_stop(transient_backoff_seconds):
                    break
            case _:
                raise AssertionError(f"Unhandled UploadResult: {result!r}")


def log_runtime_snapshot(snapshot: RuntimeSnapshot) -> None:
    latest_age = (
        "none"
        if snapshot.latest_frame_age_ms is None
        else f"{snapshot.latest_frame_age_ms:.1f}"
    )
    LOGGER.info(
        "Camera uploader stats: captured=%s uploaded=%s overwritten=%s "
        "captureErrors=%s uploadClientErrors=%s uploadTransientErrors=%s "
        "effectiveCaptureFps=%.3f effectiveUploadFps=%.3f "
        "latestFrameAgeMs=%s",
        snapshot.captured,
        snapshot.uploaded,
        snapshot.overwritten,
        snapshot.capture_errors,
        snapshot.upload_client_errors,
        snapshot.upload_transient_errors,
        snapshot.effective_capture_fps,
        snapshot.effective_upload_fps,
        latest_age,
    )


def run_service(
    settings: Settings,
    *,
    source: FrameSource | None = None,
    uploader: FrameUploader | None = None,
    stop_event: threading.Event | None = None,
    monotonic: Callable[[], float] = time.monotonic,
) -> None:
    configured_source = source or _create_source(settings)
    configured_uploader = uploader or FrameUploader(
        settings.ai_server_base_url,
        settings.camera_id,
        settings.http_timeout_seconds,
    )
    configured_stop_event = stop_event or threading.Event()
    slot = LatestFrameSlot()
    stats = RuntimeStats(started_monotonic=monotonic())
    errors: list[Exception] = []
    errors_lock = threading.Lock()

    def run_guarded(target: Callable[[], None]) -> None:
        try:
            target()
        except Exception as error:
            with errors_lock:
                errors.append(error)
            slot.request_stop(configured_stop_event)

    producer = threading.Thread(
        name="camera-capture-producer",
        target=run_guarded,
        args=(
            lambda: run_capture_producer(
                configured_source,
                slot,
                stats,
                configured_stop_event,
                settings.capture_fps,
            ),
        ),
    )
    worker = threading.Thread(
        name="camera-upload-worker",
        target=run_guarded,
        args=(
            lambda: run_upload_worker(
                slot,
                configured_uploader,
                stats,
                configured_stop_event,
                settings.upload_transient_backoff_seconds,
            ),
        ),
    )
    producer.start()
    worker.start()

    try:
        while not configured_stop_event.wait(settings.stats_interval_seconds):
            log_runtime_snapshot(stats.snapshot(monotonic()))
    finally:
        slot.request_stop(configured_stop_event)
        join_timeout = (
            settings.http_timeout_seconds
            + settings.upload_transient_backoff_seconds
            + 1.0
        )
        producer.join(timeout=join_timeout)
        worker.join(timeout=join_timeout)
        if producer.is_alive() or worker.is_alive():
            errors.append(RuntimeError("camera uploader worker did not stop in time"))
        try:
            configured_uploader.close()
        finally:
            configured_source.close()
        log_runtime_snapshot(stats.snapshot(monotonic()))

    if errors:
        raise errors[0]


def _create_source(settings: Settings) -> FrameSource:
    if settings.camera_source == "file":
        if settings.test_frame_path is None:
            raise ValueError("TEST_FRAME_PATH is required when CAMERA_SOURCE=file")
        return FileFrameSource(settings.test_frame_path)
    return Picamera2FrameSource(
        settings.frame_width,
        settings.frame_height,
        settings.capture_fps,
    )


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    stop_event = threading.Event()

    def request_stop(signum: int, frame: object) -> None:
        del frame
        LOGGER.info("Shutdown requested by signal %s", signum)
        stop_event.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        settings = Settings.from_env()
        LOGGER.info(
            "Starting camera uploader: camera_id=%s source=%s capture_fps=%s",
            settings.camera_id,
            settings.camera_source,
            settings.capture_fps,
        )
        run_service(settings, stop_event=stop_event)
    except (OSError, RuntimeError, ValueError) as error:
        LOGGER.error("Camera uploader failed to start: %s", error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
