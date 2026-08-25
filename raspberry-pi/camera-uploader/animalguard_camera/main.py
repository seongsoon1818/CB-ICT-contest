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
from animalguard_camera.settings import Settings
from animalguard_camera.uploader import FrameUploader


LOGGER = logging.getLogger(__name__)


def run_capture_loop(
    source: FrameSource,
    uploader: FrameUploader,
    interval_seconds: float,
    stop_event: threading.Event,
    *,
    wait: Callable[[float], bool] | None = None,
    monotonic: Callable[[], float] = time.monotonic,
    now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
) -> None:
    wait_for_stop = wait or stop_event.wait
    while not stop_event.is_set():
        cycle_started_at = monotonic()
        try:
            frame = source.capture_jpeg()
            captured_at = now()
            uploader.upload(frame, captured_at)
        except Exception as error:
            LOGGER.warning(
                "Frame capture failed; continuing with the next cycle (%s)",
                type(error).__name__,
            )

        remaining = interval_seconds - (monotonic() - cycle_started_at)
        if remaining > 0 and wait_for_stop(remaining):
            break


def run_service(
    settings: Settings,
    *,
    source: FrameSource | None = None,
    uploader: FrameUploader | None = None,
    stop_event: threading.Event | None = None,
    wait: Callable[[float], bool] | None = None,
) -> None:
    configured_source = source or _create_source(settings)
    configured_uploader = uploader or FrameUploader(
        settings.ai_server_base_url,
        settings.camera_id,
        settings.http_timeout_seconds,
    )
    configured_stop_event = stop_event or threading.Event()
    try:
        run_capture_loop(
            configured_source,
            configured_uploader,
            settings.frame_interval_seconds,
            configured_stop_event,
            wait=wait,
        )
    finally:
        try:
            configured_uploader.close()
        finally:
            configured_source.close()
        LOGGER.info("Camera uploader stopped")


def _create_source(settings: Settings) -> FrameSource:
    if settings.camera_source == "file":
        if settings.test_frame_path is None:
            raise ValueError("TEST_FRAME_PATH is required when CAMERA_SOURCE=file")
        return FileFrameSource(settings.test_frame_path)
    return Picamera2FrameSource(settings.frame_width, settings.frame_height)


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
            "Starting camera uploader: camera_id=%s source=%s interval_seconds=%s",
            settings.camera_id,
            settings.camera_source,
            settings.frame_interval_seconds,
        )
        run_service(settings, stop_event=stop_event)
    except (OSError, RuntimeError, ValueError) as error:
        LOGGER.error("Camera uploader failed to start: %s", error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
