from __future__ import annotations

import logging
from dataclasses import dataclass


LOGGER = logging.getLogger(__name__)

MOCK_ACTIONS = {
    "ROTATE_CAMERA_LEFT": "카메라 서보 왼쪽 회전 mock",
    "ROTATE_CAMERA_RIGHT": "카메라 서보 오른쪽 회전 mock",
    "SOUND_ALERT": "스피커 경고 mock",
    "DETERRENT_FULL": "억제 모터 + 스피커 ON mock",
    "STOP_DETERRENT": "억제 모터 + 스피커 OFF mock",
}


@dataclass(frozen=True)
class MockExecution:
    command: str
    duration_ms: int | None
    action: str


class MockGPIO:
    def __init__(self) -> None:
        self.executions: list[MockExecution] = []

    @property
    def execution_count(self) -> int:
        return len(self.executions)

    def execute(self, command: str, duration_ms: int | None) -> MockExecution:
        action = MOCK_ACTIONS[command]
        execution = MockExecution(command, duration_ms, action)
        self.executions.append(execution)
        LOGGER.info(
            "Mock GPIO 실행: command=%s durationMs=%s action=%s",
            command,
            duration_ms,
            action,
        )
        return execution
