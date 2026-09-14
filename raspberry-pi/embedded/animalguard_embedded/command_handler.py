from __future__ import annotations

import json
import logging
from datetime import UTC, datetime
from threading import Condition, Thread
from time import monotonic
from typing import Any, Callable

from .contracts import (
    Command,
    CommandValidationError,
    UnsupportedCommandError,
    ack_processed_at,
    build_ack_payload,
    parse_command,
)
from .dedup_store import DedupStore
from .gpio_adapter import GPIOAdapter


LOGGER = logging.getLogger(__name__)
PublishAck = Callable[[dict[str, str]], None]


class CommandHandler:
    def __init__(
        self,
        device_id: str,
        store: DedupStore,
        gpio: GPIOAdapter,
        max_motor_duration_ms: int,
        max_sound_duration_ms: int,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.device_id = device_id
        self.store = store
        self.gpio = gpio
        self.max_motor_duration_ms = max_motor_duration_ms
        self.max_sound_duration_ms = max_sound_duration_ms
        self.clock = clock or (lambda: datetime.now(UTC))
        self._condition = Condition()
        self._workers: set[Thread] = set()

    def handle(self, raw_payload: bytes | str, publish_ack: PublishAck) -> None:
        payload = self._decode_payload(raw_payload)
        if payload is None:
            return

        try:
            command = parse_command(payload, self.device_id)
        except (UnsupportedCommandError, CommandValidationError) as exc:
            LOGGER.warning("command payload rejected: %s", exc)
            self._publish_rejected(payload, publish_ack)
            return

        now = self.clock()
        if now >= command.expires_at:
            expired = build_ack_payload(
                command.command_id,
                self.device_id,
                "EXPIRED",
                now,
            )
            self._reserve_or_republish(expired, publish_ack)
            return

        if not self._duration_within_safety_limit(command):
            LOGGER.warning(
                "command duration exceeds configured safety limit: commandId=%s command=%s",
                command.command_id,
                command.command,
            )
            self._publish_rejected(payload, publish_ack)
            return

        acknowledged = build_ack_payload(
            command.command_id,
            self.device_id,
            "ACKNOWLEDGED",
            now,
        )
        if not self._reserve_or_republish(acknowledged, publish_ack):
            return

        if command.command in {"SOUND_ALERT", "DETERRENT_FULL"}:
            self._start_worker(command, publish_ack)
            return
        self._execute_and_finish(command, publish_ack)

    def reject(
        self,
        raw_payload: bytes | str,
        publish_ack: PublishAck,
        reason: str,
    ) -> None:
        LOGGER.warning("command MQTT envelope rejected: %s", reason)
        payload = self._decode_payload(raw_payload)
        if payload is not None:
            self._publish_rejected(payload, publish_ack)

    def wait_for_idle(self, timeout: float) -> bool:
        deadline = monotonic() + timeout
        with self._condition:
            while self._workers:
                remaining = deadline - monotonic()
                if remaining <= 0:
                    return False
                self._condition.wait(remaining)
        return True

    def close(self) -> None:
        self.gpio.stop_deterrent()
        if not self.wait_for_idle(5):
            LOGGER.error("GPIO worker did not stop within shutdown timeout")
        self.gpio.close()

    def _start_worker(self, command: Command, publish_ack: PublishAck) -> None:
        worker: Thread

        def run() -> None:
            try:
                self._execute_and_finish(command, publish_ack)
            finally:
                with self._condition:
                    self._workers.discard(worker)
                    self._condition.notify_all()

        worker = Thread(
            target=run,
            name=f"gpio-{command.command_id}",
            daemon=True,
        )
        with self._condition:
            self._workers.add(worker)
        worker.start()

    def _execute_and_finish(self, command: Command, publish_ack: PublishAck) -> None:
        try:
            self._execute_gpio(command)
        except Exception:
            LOGGER.exception(
                "GPIO command execution failed: commandId=%s command=%s",
                command.command_id,
                command.command,
            )
            status = "FAILED"
        else:
            status = "EXECUTED"

        ack = build_ack_payload(
            command.command_id,
            self.device_id,
            status,
            self.clock(),
        )
        self.store.update(
            command.command_id,
            status,
            ack,
            ack_processed_at(ack),
        )
        publish_ack(ack)

    def _execute_gpio(self, command: Command) -> None:
        match command.command:
            case "ROTATE_CAMERA_LEFT":
                self.gpio.rotate_left()
            case "ROTATE_CAMERA_RIGHT":
                self.gpio.rotate_right()
            case "SOUND_ALERT":
                self.gpio.sound_alert(_required_duration(command))
            case "DETERRENT_FULL":
                self.gpio.deterrent_full(_required_duration(command))
            case "STOP_DETERRENT":
                self.gpio.stop_deterrent()
            case _:
                raise RuntimeError(f"unreachable command: {command.command}")

    def _duration_within_safety_limit(self, command: Command) -> bool:
        if command.command == "SOUND_ALERT":
            return _required_duration(command) <= self.max_sound_duration_ms
        if command.command == "DETERRENT_FULL":
            duration = _required_duration(command)
            return duration <= min(
                self.max_motor_duration_ms,
                self.max_sound_duration_ms,
            )
        return True

    def _reserve_or_republish(
        self,
        ack: dict[str, str],
        publish_ack: PublishAck,
    ) -> bool:
        inserted = self.store.reserve(
            ack["commandId"],
            ack["deviceId"],
            ack["status"],
            ack,
            ack_processed_at(ack),
        )
        if inserted:
            publish_ack(ack)
            return True

        existing = self.store.get(ack["commandId"])
        if existing is None:
            raise RuntimeError("duplicate commandId row disappeared")
        if existing.device_id != self.device_id:
            LOGGER.error(
                "dedup database contains command for another device: commandId=%s",
                existing.command_id,
            )
            return False
        LOGGER.info(
            "duplicate command received without GPIO execution: commandId=%s status=%s",
            existing.command_id,
            existing.status,
        )
        publish_ack(existing.ack_payload)
        return False

    def _publish_rejected(
        self,
        payload: dict[str, Any],
        publish_ack: PublishAck,
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
            command_id,
            self.device_id,
            "FAILED",
            self.clock(),
        )
        self._reserve_or_republish(failed, publish_ack)

    def _decode_payload(self, raw_payload: bytes | str) -> dict[str, Any] | None:
        try:
            payload = json.loads(raw_payload)
        except (json.JSONDecodeError, UnicodeDecodeError, TypeError):
            LOGGER.error("command payload is not valid JSON")
            return None
        if not isinstance(payload, dict):
            LOGGER.error("command payload is not a JSON object")
            return None
        return payload


def _required_duration(command: Command) -> int:
    if command.duration_ms is None:
        raise RuntimeError(f"duration missing after validation: {command.command}")
    return command.duration_ms
