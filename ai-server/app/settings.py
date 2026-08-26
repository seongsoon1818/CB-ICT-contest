import os
from dataclasses import dataclass, field
from math import isfinite
from pathlib import Path
from typing import Literal, cast

MockResult = Literal["detected", "empty"]
InferenceMode = Literal["mock", "model"]
FrameEvidenceMode = Literal["off", "rolling"]


@dataclass(frozen=True)
class FrameEvidenceSettings:
    mode: FrameEvidenceMode = "off"
    directory: Path | None = None
    max_files_per_camera: int = 60
    min_interval_seconds: float = 1.0
    max_bytes_per_camera: int = 100 * 1024 * 1024


@dataclass(frozen=True)
class Settings:
    backend_base_url: str | None
    mock_result: MockResult = "detected"
    inference_mode: InferenceMode = "mock"
    model_bundle_dir: Path | None = None
    frame_evidence: FrameEvidenceSettings = field(
        default_factory=FrameEvidenceSettings
    )

    @classmethod
    def from_env(cls) -> "Settings":
        backend_base_url = os.getenv("BACKEND_BASE_URL") or None
        mock_result = os.getenv("MOCK_RESULT", "detected")
        if mock_result not in {"detected", "empty"}:
            raise ValueError("MOCK_RESULT must be 'detected' or 'empty'")
        inference_mode = os.getenv("INFERENCE_MODE", "mock")
        if inference_mode not in {"mock", "model"}:
            raise ValueError("INFERENCE_MODE must be 'mock' or 'model'")
        model_bundle_dir_value = os.getenv("MODEL_BUNDLE_DIR") or None
        frame_evidence_mode = os.getenv("FRAME_EVIDENCE_MODE", "off").strip()
        if frame_evidence_mode not in {"off", "rolling"}:
            raise ValueError("FRAME_EVIDENCE_MODE must be 'off' or 'rolling'")
        frame_evidence_directory_value = os.getenv(
            "FRAME_EVIDENCE_DIR", ""
        ).strip()
        if frame_evidence_mode == "rolling" and not frame_evidence_directory_value:
            raise ValueError(
                "FRAME_EVIDENCE_DIR is required when "
                "FRAME_EVIDENCE_MODE=rolling"
            )
        return cls(
            backend_base_url=backend_base_url,
            mock_result=cast(MockResult, mock_result),
            inference_mode=cast(InferenceMode, inference_mode),
            model_bundle_dir=(
                Path(model_bundle_dir_value)
                if model_bundle_dir_value is not None
                else None
            ),
            frame_evidence=FrameEvidenceSettings(
                mode=cast(FrameEvidenceMode, frame_evidence_mode),
                directory=(
                    Path(frame_evidence_directory_value)
                    if frame_evidence_directory_value
                    else None
                ),
                max_files_per_camera=_positive_int_env(
                    "FRAME_EVIDENCE_MAX_FILES_PER_CAMERA",
                    default=60,
                ),
                min_interval_seconds=_non_negative_float_env(
                    "FRAME_EVIDENCE_MIN_INTERVAL_SECONDS",
                    default=1.0,
                ),
                max_bytes_per_camera=_positive_int_env(
                    "FRAME_EVIDENCE_MAX_BYTES_PER_CAMERA",
                    default=100 * 1024 * 1024,
                ),
            ),
        )


def _positive_int_env(name: str, *, default: int) -> int:
    raw_value = os.getenv(name, str(default))
    try:
        value = int(raw_value)
    except ValueError as error:
        raise ValueError(f"{name} must be an integer") from error
    if value <= 0:
        raise ValueError(f"{name} must be greater than 0")
    return value


def _non_negative_float_env(name: str, *, default: float) -> float:
    raw_value = os.getenv(name, str(default))
    try:
        value = float(raw_value)
    except ValueError as error:
        raise ValueError(f"{name} must be a number") from error
    if not isfinite(value):
        raise ValueError(f"{name} must be finite")
    if value < 0:
        raise ValueError(f"{name} must be greater than or equal to 0")
    return value
