import os
from dataclasses import dataclass
from typing import Literal, cast


MockResult = Literal["detected", "empty"]


@dataclass(frozen=True)
class Settings:
    backend_base_url: str | None
    mock_result: MockResult = "detected"

    @classmethod
    def from_env(cls) -> "Settings":
        backend_base_url = os.getenv("BACKEND_BASE_URL") or None
        mock_result = os.getenv("MOCK_RESULT", "detected")
        if mock_result not in {"detected", "empty"}:
            raise ValueError("MOCK_RESULT must be 'detected' or 'empty'")
        return cls(
            backend_base_url=backend_base_url,
            mock_result=cast(MockResult, mock_result),
        )
