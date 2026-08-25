from __future__ import annotations

import logging
from dataclasses import dataclass


LOGGER = logging.getLogger(__name__)

MOCK_ACTIONS = {
    "DETERRENT_LEVEL_1": "LED mock",
    "DETERRENT_LEVEL_2": "MOTOR/SPEAKER/LED mock",
    "DETERRENT_LEVEL_3": "강화 동작 mock",
    "STOP_DETERRENT": "모든 장치 OFF mock",
}


@dataclass(frozen=True)
class MockExecution:
    command: str
    duration_ms: int
    action: str


class MockGPIO:
    def __init__(self) -> None:
        self.executions: list[MockExecution] = []

    @property
    def execution_count(self) -> int:
        return len(self.executions)

    def execute(self, command: str, duration_ms: int) -> MockExecution:
        action = MOCK_ACTIONS[command]
        execution = MockExecution(command, duration_ms, action)
        self.executions.append(execution)
        LOGGER.info(
            "Mock GPIO 실행: command=%s durationMs=%d action=%s",
            command,
            duration_ms,
            action,
        )
        return execution
