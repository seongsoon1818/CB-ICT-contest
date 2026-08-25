from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from animalguard_mqtt.contracts import (
    CommandValidationError,
    UnsupportedCommandError,
    ack_topic,
    build_status_payload,
    command_topic,
    parse_command,
    status_topic,
)


def valid_command(**overrides):
    now = datetime.now(UTC)
    payload = {
        "commandId": "cmd-001",
        "eventId": str(uuid4()),
        "deviceId": "pi-001",
        "command": "DETERRENT_LEVEL_2",
        "durationMs": 5000,
        "issuedAt": now.isoformat(),
        "expiresAt": (now + timedelta(seconds=10)).isoformat(),
        "reason": "HIGH_RISK_MAGPIE",
    }
    payload.update(overrides)
    return payload


def test_parse_valid_command():
    command = parse_command(valid_command(), expected_device_id="pi-001")

    assert command.command_id == "cmd-001"
    assert command.command == "DETERRENT_LEVEL_2"
    assert command.duration_ms == 5000


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"deviceId": "pi-999"}, "deviceId"),
        ({"eventId": "not-a-uuid"}, "eventId"),
        ({"expiresAt": "2026-08-25T03:20:00"}, "expiresAt"),
        (
            {
                "issuedAt": "2026-08-25T03:20:00+00:00",
                "expiresAt": "2026-08-25T03:20:00+00:00",
            },
            "expiresAt",
        ),
        ({"durationMs": 0}, "durationMs"),
        ({"gpioPin": 17}, "additional fields"),
    ],
)
def test_parse_command_rejects_invalid_payload(overrides, message):
    with pytest.raises(CommandValidationError, match=message):
        parse_command(valid_command(**overrides), expected_device_id="pi-001")


def test_parse_command_rejects_unsupported_command_separately():
    with pytest.raises(UnsupportedCommandError, match="command"):
        parse_command(valid_command(command="RAW_PWM"), expected_device_id="pi-001")


def test_topics_encode_device_id_as_one_path_segment():
    assert command_topic("pi/site 1") == "animalguard/devices/pi%2Fsite%201/commands"
    assert ack_topic("pi-001") == "animalguard/devices/pi-001/acks"
    assert status_topic("pi-001") == "animalguard/devices/pi-001/status"


def test_build_status_payload_contains_timezone_and_firmware_version():
    reported_at = datetime(2026, 8, 25, 3, 20, tzinfo=UTC)

    payload = build_status_payload("pi-001", "ONLINE", reported_at)

    assert payload == {
        "deviceId": "pi-001",
        "status": "ONLINE",
        "reportedAt": "2026-08-25T03:20:00Z",
        "firmwareVersion": "mqtt-simulator-v1",
    }
