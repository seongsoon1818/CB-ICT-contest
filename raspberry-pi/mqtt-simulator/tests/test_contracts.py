from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from animalguard_mqtt.contracts import (
    COMMAND_FIELDS,
    SUPPORTED_COMMANDS,
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
        "source": "AUTOMATIC",
        "command": "SOUND_ALERT",
        "durationMs": 2000,
        "issuedAt": now.isoformat(),
        "expiresAt": (now + timedelta(seconds=10)).isoformat(),
        "reason": "FIRST_ANIMAL_DETECTION",
    }
    payload.update(overrides)
    return payload


def test_supported_commands_match_final_device_vocabulary_exactly():
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


def test_parse_valid_automatic_command():
    command = parse_command(valid_command(), expected_device_id="pi-001")

    assert command.command_id == "cmd-001"
    assert command.event_id is not None
    assert command.source == "AUTOMATIC"
    assert command.command == "SOUND_ALERT"
    assert command.duration_ms == 2000


def test_parse_valid_manual_command_with_null_event_and_duration():
    command = parse_command(
        valid_command(
            eventId=None,
            source="MANUAL",
            command="ROTATE_CAMERA_LEFT",
            durationMs=None,
            reason="USER_REQUEST",
        ),
        expected_device_id="pi-001",
    )

    assert command.event_id is None
    assert command.source == "MANUAL"
    assert command.command == "ROTATE_CAMERA_LEFT"
    assert command.duration_ms is None


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
def test_parse_all_allowed_source_command_combinations(source, command, duration_ms):
    event_id = str(uuid4()) if source == "AUTOMATIC" else None

    parsed = parse_command(
        valid_command(
            source=source,
            command=command,
            eventId=event_id,
            durationMs=duration_ms,
        ),
        expected_device_id="pi-001",
    )

    assert parsed.command == command


@pytest.mark.parametrize(
    ("overrides", "message"),
    [
        ({"deviceId": "pi-999"}, "deviceId"),
        ({"eventId": "not-a-uuid"}, "eventId"),
        ({"eventId": None}, "eventId"),
        (
            {
                "source": "MANUAL",
                "eventId": str(uuid4()),
                "command": "STOP_DETERRENT",
                "durationMs": None,
            },
            "eventId",
        ),
        ({"source": "SCHEDULED"}, "source"),
        ({"expiresAt": "2026-08-25T03:20:00"}, "expiresAt"),
        (
            {
                "issuedAt": "2026-08-25T03:20:00+00:00",
                "expiresAt": "2026-08-25T03:20:00+00:00",
            },
            "expiresAt",
        ),
        ({"gpioPin": 17}, "additional fields"),
    ],
)
def test_parse_command_rejects_invalid_payload(overrides, message):
    with pytest.raises(CommandValidationError, match=message):
        parse_command(valid_command(**overrides), expected_device_id="pi-001")


@pytest.mark.parametrize(
    ("source", "command"),
    [
        ("MANUAL", "SOUND_ALERT"),
        ("MANUAL", "DETERRENT_FULL"),
        ("AUTOMATIC", "ROTATE_CAMERA_LEFT"),
        ("AUTOMATIC", "ROTATE_CAMERA_RIGHT"),
    ],
)
def test_parse_command_rejects_invalid_source_command_combinations(source, command):
    event_id = str(uuid4()) if source == "AUTOMATIC" else None
    duration_ms = 2000 if command in {"SOUND_ALERT", "DETERRENT_FULL"} else None

    with pytest.raises(CommandValidationError, match="source"):
        parse_command(
            valid_command(
                source=source,
                command=command,
                eventId=event_id,
                durationMs=duration_ms,
            ),
            expected_device_id="pi-001",
        )


@pytest.mark.parametrize(
    ("command", "duration_ms"),
    [
        ("SOUND_ALERT", None),
        ("SOUND_ALERT", 0),
        ("SOUND_ALERT", True),
        ("DETERRENT_FULL", None),
        ("DETERRENT_FULL", -1),
        ("ROTATE_CAMERA_LEFT", 1000),
        ("ROTATE_CAMERA_RIGHT", 1000),
        ("STOP_DETERRENT", 1000),
    ],
)
def test_parse_command_rejects_invalid_duration_for_command(command, duration_ms):
    source = "AUTOMATIC" if command in {"SOUND_ALERT", "DETERRENT_FULL"} else "MANUAL"
    event_id = str(uuid4()) if source == "AUTOMATIC" else None

    with pytest.raises(CommandValidationError, match="durationMs"):
        parse_command(
            valid_command(
                source=source,
                command=command,
                eventId=event_id,
                durationMs=duration_ms,
            ),
            expected_device_id="pi-001",
        )


def test_parse_automatic_stop_rejects_duration():
    with pytest.raises(CommandValidationError, match="durationMs"):
        parse_command(
            valid_command(command="STOP_DETERRENT", durationMs=1000),
            expected_device_id="pi-001",
        )


def test_parse_command_requires_source_field():
    payload = valid_command()
    del payload["source"]

    with pytest.raises(CommandValidationError, match="source"):
        parse_command(payload, expected_device_id="pi-001")


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
