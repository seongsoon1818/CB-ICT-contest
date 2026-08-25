from __future__ import annotations

import json
import logging
from datetime import UTC, datetime
from typing import Any, Callable

from .contracts import (
    CommandValidationError,
    UnsupportedCommandError,
    build_ack_payload,
    format_timestamp,
    parse_command,
)
from .dedup_store import DedupStore
from .mock_gpio import MockGPIO


LOGGER = logging.getLogger(__name__)
PublishAck = Callable[[dict[str, str]], None]


class CommandHandler:
    def __init__(
        self,
        device_id: str,
        store: DedupStore,
        gpio: MockGPIO,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.device_id = device_id
        self.store = store
        self.gpio = gpio
        self.clock = clock or (lambda: datetime.now(UTC))

    def handle(self, raw_payload: bytes | str, publish_ack: PublishAck) -> None:
        payload = self._decode_payload(raw_payload)
        if payload is None:
            return

        try:
            command = parse_command(payload, self.device_id)
        except UnsupportedCommandError as exc:
            LOGGER.warning("지원하지 않는 command 거절: %s", exc)
            self._publish_rejected(payload, publish_ack)
            return
        except CommandValidationError as exc:
            LOGGER.warning("command payload 검증 실패: %s", exc)
            self._publish_rejected(payload, publish_ack)
            return

        now = self.clock()
        if now >= command.expires_at:
            expired = build_ack_payload(
                command.command_id, self.device_id, "EXPIRED", now
            )
            self._reserve_or_republish(expired, publish_ack)
            return

        acknowledged = build_ack_payload(
            command.command_id, self.device_id, "ACKNOWLEDGED", now
        )
        if not self._reserve_or_republish(acknowledged, publish_ack):
            return

        try:
            self.gpio.execute(command.command, command.duration_ms)
        except Exception:
            LOGGER.exception("Mock GPIO 실행 실패: commandId=%s", command.command_id)
            failed_at = self.clock()
            failed = build_ack_payload(
                command.command_id, self.device_id, "FAILED", failed_at
            )
            self._update_and_publish(failed, publish_ack)
            return

        executed_at = self.clock()
        executed = build_ack_payload(
            command.command_id, self.device_id, "EXECUTED", executed_at
        )
        self._update_and_publish(executed, publish_ack)

    def _decode_payload(self, raw_payload: bytes | str) -> dict[str, Any] | None:
        try:
            payload = json.loads(raw_payload)
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
            LOGGER.error("command payload가 유효한 JSON이 아님")
            return None
        if not isinstance(payload, dict):
            LOGGER.error("command payload가 JSON object가 아님")
            return None
        return payload

    def _publish_rejected(
        self, payload: dict[str, Any], publish_ack: PublishAck
    ) -> None:
        command_id = payload.get("commandId")
        device_id = payload.get("deviceId")
        if (
            not isinstance(command_id, str)
            or not command_id.strip()
            or device_id != self.device_id
        ):
            return
        failed = build_ack_payload(
            command_id, self.device_id, "FAILED", self.clock()
        )
        self._reserve_or_republish(failed, publish_ack)

    def _reserve_or_republish(
        self, ack: dict[str, str], publish_ack: PublishAck
    ) -> bool:
        inserted = self.store.reserve(
            ack["commandId"],
            ack["deviceId"],
            ack["status"],
            _ack_timestamp(ack),
            ack,
        )
        if inserted:
            publish_ack(ack)
            return True

        existing = self.store.get(ack["commandId"])
        if existing is None:
            raise RuntimeError("중복 commandId의 저장 결과를 조회할 수 없음")
        LOGGER.info(
            "중복 command 수신: commandId=%s status=%s",
            existing.command_id,
            existing.status,
        )
        publish_ack(existing.ack_payload)
        return False

    def _update_and_publish(
        self, ack: dict[str, str], publish_ack: PublishAck
    ) -> None:
        self.store.update(
            ack["commandId"], ack["status"], _ack_timestamp(ack), ack
        )
        publish_ack(ack)


def _ack_timestamp(ack: dict[str, str]) -> str:
    timestamp_fields = {
        "ACKNOWLEDGED": "acknowledgedAt",
        "EXECUTED": "executedAt",
        "EXPIRED": "expiredAt",
        "FAILED": "failedAt",
    }
    timestamp = ack[timestamp_fields[ack["status"]]]
    return format_timestamp(datetime.fromisoformat(timestamp))
