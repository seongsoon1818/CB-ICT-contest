from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Mapping
from urllib.parse import quote
from uuid import UUID


COMMAND_FIELDS = {
    "commandId",
    "eventId",
    "deviceId",
    "command",
    "durationMs",
    "issuedAt",
    "expiresAt",
    "reason",
}
SUPPORTED_COMMANDS = {
    "DETERRENT_LEVEL_1",
    "DETERRENT_LEVEL_2",
    "DETERRENT_LEVEL_3",
    "STOP_DETERRENT",
}
ACK_TIMESTAMP_FIELDS = {
    "ACKNOWLEDGED": "acknowledgedAt",
    "EXECUTED": "executedAt",
    "EXPIRED": "expiredAt",
    "FAILED": "failedAt",
}
STATUS_VALUES = {"ONLINE", "OFFLINE", "DEGRADED", "MAINTENANCE"}


class CommandValidationError(ValueError):
    pass


class UnsupportedCommandError(CommandValidationError):
    pass


@dataclass(frozen=True)
class Command:
    command_id: str
    event_id: UUID
    device_id: str
    command: str
    duration_ms: int
    issued_at: datetime
    expires_at: datetime
    reason: str


def parse_command(payload: Mapping[str, Any], expected_device_id: str) -> Command:
    if not isinstance(payload, Mapping):
        raise CommandValidationError("payload must be a JSON object")

    missing = COMMAND_FIELDS - payload.keys()
    if missing:
        raise CommandValidationError(f"missing fields: {', '.join(sorted(missing))}")
    additional = payload.keys() - COMMAND_FIELDS
    if additional:
        raise CommandValidationError(
            f"additional fields are not allowed: {', '.join(sorted(additional))}"
        )

    command_id = _non_empty_string(payload["commandId"], "commandId")
    device_id = _non_empty_string(payload["deviceId"], "deviceId")
    if device_id != expected_device_id:
        raise CommandValidationError("deviceId does not match simulator settings")

    event_id_value = _non_empty_string(payload["eventId"], "eventId")
    try:
        event_id = UUID(event_id_value)
    except ValueError as exc:
        raise CommandValidationError("eventId must be a UUID") from exc

    duration_ms = payload["durationMs"]
    if isinstance(duration_ms, bool) or not isinstance(duration_ms, int):
        raise CommandValidationError("durationMs must be an integer")
    if duration_ms <= 0:
        raise CommandValidationError("durationMs must be positive")

    issued_at = _timezone_datetime(payload["issuedAt"], "issuedAt")
    expires_at = _timezone_datetime(payload["expiresAt"], "expiresAt")
    if expires_at <= issued_at:
        raise CommandValidationError("expiresAt must be later than issuedAt")

    command = _non_empty_string(payload["command"], "command")
    reason = _non_empty_string(payload["reason"], "reason")
    if command not in SUPPORTED_COMMANDS:
        raise UnsupportedCommandError(f"unsupported command: {command}")

    return Command(
        command_id=command_id,
        event_id=event_id,
        device_id=device_id,
        command=command,
        duration_ms=duration_ms,
        issued_at=issued_at,
        expires_at=expires_at,
        reason=reason,
    )


def command_topic(device_id: str) -> str:
    return _device_topic(device_id, "commands")


def ack_topic(device_id: str) -> str:
    return _device_topic(device_id, "acks")


def status_topic(device_id: str) -> str:
    return _device_topic(device_id, "status")


def build_ack_payload(
    command_id: str, device_id: str, status: str, timestamp: datetime
) -> dict[str, str]:
    try:
        timestamp_field = ACK_TIMESTAMP_FIELDS[status]
    except KeyError as exc:
        raise ValueError(f"unsupported ACK status: {status}") from exc
    return {
        "commandId": command_id,
        "deviceId": device_id,
        "status": status,
        timestamp_field: format_timestamp(timestamp),
    }


def build_status_payload(
    device_id: str, status: str, reported_at: datetime
) -> dict[str, str]:
    if status not in STATUS_VALUES:
        raise ValueError(f"unsupported device status: {status}")
    return {
        "deviceId": device_id,
        "status": status,
        "reportedAt": format_timestamp(reported_at),
        "firmwareVersion": "mqtt-simulator-v1",
    }


def format_timestamp(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("timestamp must include a timezone")
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _device_topic(device_id: str, suffix: str) -> str:
    if not device_id.strip():
        raise ValueError("deviceId must not be empty")
    return f"animalguard/devices/{quote(device_id, safe='')}/{suffix}"


def _non_empty_string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise CommandValidationError(f"{field} must be a non-empty string")
    return value


def _timezone_datetime(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        raise CommandValidationError(f"{field} must be an ISO 8601 date-time")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise CommandValidationError(
            f"{field} must be an ISO 8601 date-time"
        ) from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise CommandValidationError(f"{field} must include a timezone")
    return parsed
