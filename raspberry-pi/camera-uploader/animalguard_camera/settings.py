import os
import re
from dataclasses import dataclass
from math import isfinite
from pathlib import Path
from typing import Literal, Mapping, cast


CAMERA_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
CameraSourceName = Literal["picamera2", "file"]
MAX_CAPTURE_FPS = 30.0


@dataclass(frozen=True)
class Settings:
    ai_server_base_url: str
    camera_id: str
    capture_fps: float = 30.0
    http_timeout_seconds: float = 10.0
    upload_transient_backoff_seconds: float = 1.0
    stats_interval_seconds: float = 10.0
    camera_source: CameraSourceName = "picamera2"
    test_frame_path: Path | None = None
    frame_width: int = 1280
    frame_height: int = 720

    @property
    def frame_interval_seconds(self) -> float:
        return 1.0 / self.capture_fps

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "Settings":
        values = os.environ if environ is None else environ
        ai_server_base_url = values.get("AI_SERVER_BASE_URL", "").strip()
        camera_id = values.get("CAMERA_ID", "").strip()
        if not ai_server_base_url:
            raise ValueError("AI_SERVER_BASE_URL is required")
        if not camera_id:
            raise ValueError("CAMERA_ID is required")
        if len(camera_id) > 64 or CAMERA_ID_PATTERN.fullmatch(camera_id) is None:
            raise ValueError(
                "CAMERA_ID must match ^[A-Za-z0-9][A-Za-z0-9._-]*$ "
                "and contain at most 64 characters"
            )

        if "FRAME_INTERVAL_SECONDS" in values:
            raise ValueError(
                "FRAME_INTERVAL_SECONDS is deprecated; use CAPTURE_FPS"
            )

        capture_fps = _bounded_positive_float(
            values.get("CAPTURE_FPS", "30"),
            "CAPTURE_FPS",
            maximum=MAX_CAPTURE_FPS,
        )
        http_timeout_seconds = _positive_float(
            values.get("HTTP_TIMEOUT_SECONDS", "10"),
            "HTTP_TIMEOUT_SECONDS",
        )
        camera_source = values.get("CAMERA_SOURCE", "picamera2").strip().lower()
        if camera_source not in {"picamera2", "file"}:
            raise ValueError("CAMERA_SOURCE must be 'picamera2' or 'file'")

        test_frame_value = values.get("TEST_FRAME_PATH", "").strip()
        test_frame_path = Path(test_frame_value) if test_frame_value else None
        if camera_source == "file" and test_frame_path is None:
            raise ValueError("TEST_FRAME_PATH is required when CAMERA_SOURCE=file")

        return cls(
            ai_server_base_url=ai_server_base_url.rstrip("/"),
            camera_id=camera_id,
            capture_fps=capture_fps,
            http_timeout_seconds=http_timeout_seconds,
            upload_transient_backoff_seconds=_non_negative_float(
                values.get("UPLOAD_TRANSIENT_BACKOFF_SECONDS", "1"),
                "UPLOAD_TRANSIENT_BACKOFF_SECONDS",
            ),
            stats_interval_seconds=_positive_float(
                values.get("STATS_INTERVAL_SECONDS", "10"),
                "STATS_INTERVAL_SECONDS",
            ),
            camera_source=cast(CameraSourceName, camera_source),
            test_frame_path=test_frame_path,
            frame_width=_positive_int(values.get("FRAME_WIDTH", "1280"), "FRAME_WIDTH"),
            frame_height=_positive_int(
                values.get("FRAME_HEIGHT", "720"), "FRAME_HEIGHT"
            ),
        )


def _positive_float(raw_value: str, name: str) -> float:
    value = _float(raw_value, name)
    if value <= 0:
        raise ValueError(f"{name} must be greater than 0")
    return value


def _bounded_positive_float(raw_value: str, name: str, maximum: float) -> float:
    value = _positive_float(raw_value, name)
    if value > maximum:
        raise ValueError(f"{name} must be at most {maximum:g}")
    return value


def _non_negative_float(raw_value: str, name: str) -> float:
    value = _float(raw_value, name)
    if value < 0:
        raise ValueError(f"{name} must be greater than or equal to 0")
    return value


def _float(raw_value: str, name: str) -> float:
    try:
        value = float(raw_value)
    except ValueError as error:
        raise ValueError(f"{name} must be a number") from error
    if not isfinite(value):
        raise ValueError(f"{name} must be finite")
    return value

def _positive_int(raw_value: str, name: str) -> int:
    try:
        value = int(raw_value)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if value <= 0:
        raise ValueError(f"{name} must be greater than 0")
    return value
