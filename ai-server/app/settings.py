import os
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, cast


MockResult = Literal["detected", "empty"]
InferenceMode = Literal["mock", "model"]


@dataclass(frozen=True)
class Settings:
    backend_base_url: str | None
    mock_result: MockResult = "detected"
    inference_mode: InferenceMode = "mock"
    model_bundle_dir: Path | None = None

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
        return cls(
            backend_base_url=backend_base_url,
            mock_result=cast(MockResult, mock_result),
            inference_mode=cast(InferenceMode, inference_mode),
            model_bundle_dir=(
                Path(model_bundle_dir_value)
                if model_bundle_dir_value is not None
                else None
            ),
        )
