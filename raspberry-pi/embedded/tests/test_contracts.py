from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from animalguard_embedded.contracts import (
    ACK_TIMESTAMP_FIELDS,
    COMMAND_FIELDS,
    SUPPORTED_COMMANDS,
    CommandValidationError,
    ack_topic,
    build_ack_payload,
    build_status_payload,
    command_topic,
    parse_command,
    status_topic,
)


NOW = datetime(2026, 8, 26, 9, 0, tzinfo=UTC)


def valid_command(**overrides):
    payload = {
        "commandId": "command-001",
        "eventId": str(uuid4()),
        "deviceId": "pi-001",
        "source": "AUTOMATIC",
        "command": "SOUND_ALERT",
        "durationMs": 2000,
        "issuedAt": (NOW - timedelta(seconds=1)).isoformat(),
        "expiresAt": (NOW + timedelta(seconds=10)).isoformat(),
        "reason": "FIRST_ANIMAL_DETECTION",
    }
    payload.update(overrides)
    return payload


def test_topic_and_command_vocabulary_matches_mqtt_v1_exactly():
    assert COMMAND_FIELDS == {
        "commandId",
        "eventId",
        "deviceId",
        "source",
        "command",
        "durationMs",
        "issuedAt",
        "expiresAt",
        "reason",
    }
    assert SUPPORTED_COMMANDS == {
        "ROTATE_CAMERA_LEFT",
        "ROTATE_CAMERA_RIGHT",
        "SOUND_ALERT",
        "DETERRENT_FULL",
        "STOP_DETERRENT",
    }
    assert command_topic("pi/site 1") == "animalguard/devices/pi%2Fsite%201/commands"
    assert ack_topic("pi-001") == "animalguard/devices/pi-001/acks"
    assert status_topic("pi-001") == "animalguard/devices/pi-001/status"


@pytest.mark.parametrize(
    ("source", "command", "duration_ms"),
    [
        ("AUTOMATIC", "SOUND_ALERT", 2000),
        ("AUTOMATIC", "DETERRENT_FULL", 5000),
        ("AUTOMATIC", "STOP_DETERRENT", None),
        ("MANUAL", "ROTATE_CAMERA_LEFT", None),
        ("MANUAL", "ROTATE_CAMERA_RIGHT", None),
        ("MANUAL", "STOP_DETERRENT", None),
    ],
)
def test_parser_accepts_every_allowed_source_command(source, command, duration_ms):
    parsed = parse_command(
        valid_command(
            source=source,
            command=command,
            eventId=str(uuid4()) if source == "AUTOMATIC" else None,
            durationMs=duration_ms,
        ),
        "pi-001",
    )

    assert parsed.source == source
    assert parsed.command == command
    assert parsed.duration_ms == duration_ms


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"source": "MANUAL", "eventId": str(uuid4()), "command": "STOP_DETERRENT", "durationMs": None}, "eventId"),
        ({"eventId": None}, "eventId"),
        ({"eventId": "not-a-uuid"}, "eventId"),
        ({"source": "MANUAL", "eventId": None, "command": "SOUND_ALERT"}, "source"),
        ({"source": "AUTOMATIC", "command": "ROTATE_CAMERA_LEFT", "durationMs": None}, "source"),
        ({"durationMs": 0}, "durationMs"),
        ({"durationMs": True}, "durationMs"),
        ({"command": "STOP_DETERRENT", "durationMs": 1000}, "durationMs"),
        ({"issuedAt": "2026-08-26T09:00:00"}, "issuedAt"),
        ({"expiresAt": "2026-08-26T08:00:00Z"}, "expiresAt"),
        ({"gpioPin": 17}, "additional fields"),
        ({"deviceId": "pi-999"}, "deviceId"),
    ],
)
def test_parser_rejects_invalid_contract(overrides, message):
    with pytest.raises(CommandValidationError, match=message):
        parse_command(valid_command(**overrides), "pi-001")


@pytest.mark.parametrize("status", ACK_TIMESTAMP_FIELDS)
def test_ack_uses_exactly_one_matching_timestamp(status):
    payload = build_ack_payload("command-001", "pi-001", status, NOW)

    assert payload == {
        "commandId": "command-001",
        "deviceId": "pi-001",
        "status": status,
        ACK_TIMESTAMP_FIELDS[status]: "2026-08-26T09:00:00Z",
    }


@pytest.mark.parametrize("status", ["ONLINE", "OFFLINE", "DEGRADED", "MAINTENANCE"])
def test_status_contract_has_timezone_and_firmware(status):
    payload = build_status_payload("pi-001", status, NOW)

    assert payload == {
        "deviceId": "pi-001",
        "status": status,
        "reportedAt": "2026-08-26T09:00:00Z",
        "firmwareVersion": "animalguard-embedded-v1",
    }
