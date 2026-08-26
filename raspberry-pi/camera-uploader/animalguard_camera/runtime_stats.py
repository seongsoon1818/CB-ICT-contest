import threading
from dataclasses import dataclass


@dataclass(frozen=True)
class RuntimeSnapshot:
    captured: int
    uploaded: int
    overwritten: int
    capture_errors: int
    upload_client_errors: int
    upload_transient_errors: int
    effective_capture_fps: float
    effective_upload_fps: float
    latest_frame_age_ms: float | None


class RuntimeStats:
    def __init__(self, started_monotonic: float) -> None:
        self._lock = threading.Lock()
        self._last_snapshot_monotonic = started_monotonic
        self._last_snapshot_captured = 0
        self._last_snapshot_uploaded = 0
        self._captured = 0
        self._uploaded = 0
        self._overwritten = 0
        self._capture_errors = 0
        self._upload_client_errors = 0
        self._upload_transient_errors = 0
        self._latest_capture_monotonic: float | None = None

    def record_captured(self, captured_monotonic: float) -> None:
        with self._lock:
            self._captured += 1
            self._latest_capture_monotonic = captured_monotonic

    def record_uploaded(self) -> None:
        with self._lock:
            self._uploaded += 1

    def record_overwritten(self) -> None:
        with self._lock:
            self._overwritten += 1

    def record_capture_error(self) -> None:
        with self._lock:
            self._capture_errors += 1

    def record_upload_client_error(self) -> None:
        with self._lock:
            self._upload_client_errors += 1

    def record_upload_transient_error(self) -> None:
        with self._lock:
            self._upload_transient_errors += 1

    def snapshot(self, now_monotonic: float) -> RuntimeSnapshot:
        with self._lock:
            elapsed = max(0.0, now_monotonic - self._last_snapshot_monotonic)
            captured_delta = self._captured - self._last_snapshot_captured
            uploaded_delta = self._uploaded - self._last_snapshot_uploaded
            capture_fps = captured_delta / elapsed if elapsed > 0 else 0.0
            upload_fps = uploaded_delta / elapsed if elapsed > 0 else 0.0
            age_ms = None
            if self._latest_capture_monotonic is not None:
                age_ms = max(
                    0.0,
                    (now_monotonic - self._latest_capture_monotonic) * 1000,
                )
            snapshot = RuntimeSnapshot(
                captured=self._captured,
                uploaded=self._uploaded,
                overwritten=self._overwritten,
                capture_errors=self._capture_errors,
                upload_client_errors=self._upload_client_errors,
                upload_transient_errors=self._upload_transient_errors,
                effective_capture_fps=capture_fps,
                effective_upload_fps=upload_fps,
                latest_frame_age_ms=age_ms,
            )
            if elapsed > 0:
                self._last_snapshot_monotonic = now_monotonic
                self._last_snapshot_captured = self._captured
                self._last_snapshot_uploaded = self._uploaded
            return snapshot
