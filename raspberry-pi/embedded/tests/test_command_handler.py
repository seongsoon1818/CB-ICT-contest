import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import pytest

from animalguard_embedded.command_handler import CommandHandler
from animalguard_embedded.dedup_store import DedupStore
from tests.helpers import FakeGPIO


NOW = datetime(2026, 8, 26, 9, 0, tzinfo=UTC)


def command_payload(**overrides):
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
    return json.dumps(payload)


def make_handler(database, gpio=None):
    store = DedupStore(database)
    gpio = gpio or FakeGPIO()
    handler = CommandHandler(
        "pi-001",
        store,
        gpio,
        max_motor_duration_ms=5000,
        max_sound_duration_ms=5000,
        clock=lambda: NOW,
    )
    return handler, store, gpio


@pytest.mark.parametrize(
    ("source", "command", "duration_ms"),
    [
        ("MANUAL", "ROTATE_CAMERA_LEFT", None),
        ("MANUAL", "ROTATE_CAMERA_RIGHT", None),
        ("AUTOMATIC", "SOUND_ALERT", 2000),
        ("AUTOMATIC", "DETERRENT_FULL", 5000),
        ("MANUAL", "STOP_DETERRENT", None),
    ],
)
def test_executes_all_five_commands_with_acknowledged_then_executed(
    tmp_path,
    source,
    command,
    duration_ms,
):
    handler, store, gpio = make_handler(tmp_path / f"{command}.db")
    published = []

    handler.handle(
        command_payload(
            source=source,
            command=command,
            eventId=str(uuid4()) if source == "AUTOMATIC" else None,
            durationMs=duration_ms,
        ),
        published.append,
    )
    assert handler.wait_for_idle(2) is True

    assert [ack["status"] for ack in published] == ["ACKNOWLEDGED", "EXECUTED"]
    assert set(published[0]) == {
        "commandId",
        "deviceId",
        "status",
        "acknowledgedAt",
    }
    assert set(published[1]) == {
        "commandId",
        "deviceId",
        "status",
        "executedAt",
    }
    assert gpio.actions[0] == (command, duration_ms)
    assert store.get("command-001").status == "EXECUTED"
    store.close()


def test_expired_command_never_executes_and_uses_expired_at(tmp_path):
    handler, store, gpio = make_handler(tmp_path / "expired.db")
    published = []

    handler.handle(
        command_payload(expiresAt=(NOW - timedelta(milliseconds=1)).isoformat()),
        published.append,
    )

    assert [ack["status"] for ack in published] == ["EXPIRED"]
    assert set(published[0]) == {"commandId", "deviceId", "status", "expiredAt"}
    assert gpio.execution_count == 0
    store.close()


@pytest.mark.parametrize(
    ("command", "duration", "motor_limit", "sound_limit"),
    [
        ("SOUND_ALERT", 5001, 10000, 5000),
        ("DETERRENT_FULL", 5001, 5000, 10000),
        ("DETERRENT_FULL", 5001, 10000, 5000),
    ],
)
def test_local_duration_safety_limit_fails_without_gpio(
    tmp_path,
    command,
    duration,
    motor_limit,
    sound_limit,
):
    store = DedupStore(tmp_path / f"{command}-{motor_limit}-{sound_limit}.db")
    gpio = FakeGPIO()
    handler = CommandHandler(
        "pi-001",
        store,
        gpio,
        motor_limit,
        sound_limit,
        clock=lambda: NOW,
    )
    published = []

    handler.handle(
        command_payload(command=command, durationMs=duration),
        published.append,
    )

    assert [ack["status"] for ack in published] == ["FAILED"]
    assert "error" not in published[0]
    assert gpio.execution_count == 0
    store.close()


def test_invalid_contract_fails_without_extra_error_field_or_gpio(tmp_path):
    handler, store, gpio = make_handler(tmp_path / "invalid.db")
    published = []

    handler.handle(command_payload(source="SCHEDULED"), published.append)

    assert [ack["status"] for ack in published] == ["FAILED"]
    assert set(published[0]) == {"commandId", "deviceId", "status", "failedAt"}
    assert gpio.execution_count == 0
    store.close()


def test_duplicate_republishes_stored_ack_without_gpio(tmp_path):
    handler, store, gpio = make_handler(tmp_path / "duplicate.db")
    first = []
    duplicate = []
    payload = command_payload(
        source="MANUAL",
        command="ROTATE_CAMERA_LEFT",
        eventId=None,
        durationMs=None,
    )

    handler.handle(payload, first.append)
    handler.handle(payload, duplicate.append)

    assert gpio.execution_count == 1
    assert [ack["status"] for ack in duplicate] == ["EXECUTED"]
    assert duplicate[0] == first[-1]
    store.close()


def test_duplicate_remains_deduplicated_after_sqlite_restart(tmp_path):
    database = tmp_path / "restart.db"
    payload = command_payload(
        source="MANUAL",
        command="ROTATE_CAMERA_RIGHT",
        eventId=None,
        durationMs=None,
    )
    first_handler, first_store, first_gpio = make_handler(database)
    first_handler.handle(payload, lambda ack: None)
    first_store.close()

    second_handler, second_store, second_gpio = make_handler(database)
    published = []
    second_handler.handle(payload, published.append)

    assert first_gpio.execution_count == 1
    assert second_gpio.execution_count == 0
    assert [ack["status"] for ack in published] == ["EXECUTED"]
    second_store.close()


def test_gpio_error_is_local_log_detail_and_failed_ack(tmp_path):
    class FailingGPIO(FakeGPIO):
        def rotate_left(self):
            raise RuntimeError("fake wiring error")

    handler, store, _ = make_handler(tmp_path / "failure.db", FailingGPIO())
    published = []

    handler.handle(
        command_payload(
            source="MANUAL",
            command="ROTATE_CAMERA_LEFT",
            eventId=None,
            durationMs=None,
        ),
        published.append,
    )

    assert [ack["status"] for ack in published] == ["ACKNOWLEDGED", "FAILED"]
    assert set(published[-1]) == {"commandId", "deviceId", "status", "failedAt"}
    store.close()


def test_stop_interrupts_active_worker_without_changing_command_contract(tmp_path):
    gpio = FakeGPIO(block_timed=True)
    handler, store, _ = make_handler(tmp_path / "stop.db", gpio)
    published = []

    handler.handle(
        command_payload(commandId="deterrent-001", command="DETERRENT_FULL", durationMs=5000),
        published.append,
    )
    assert gpio.started.wait(1) is True

    handler.handle(
        command_payload(
            commandId="stop-001",
            source="MANUAL",
            command="STOP_DETERRENT",
            eventId=None,
            durationMs=None,
            reason="USER_REQUEST",
        ),
        published.append,
    )
    assert handler.wait_for_idle(2) is True

    deterrent_statuses = [
        ack["status"] for ack in published if ack["commandId"] == "deterrent-001"
    ]
    stop_statuses = [
        ack["status"] for ack in published if ack["commandId"] == "stop-001"
    ]
    assert deterrent_statuses == ["ACKNOWLEDGED", "FAILED"]
    assert stop_statuses == ["ACKNOWLEDGED", "EXECUTED"]
    assert gpio.actions[-1] == ("STOP_DETERRENT", None)
    store.close()


def test_rejected_mqtt_envelope_does_not_execute(tmp_path):
    handler, store, gpio = make_handler(tmp_path / "envelope.db")
    published = []

    handler.reject(command_payload(), published.append, "qos=0 retain=False")

    assert [ack["status"] for ack in published] == ["FAILED"]
    assert gpio.execution_count == 0
    store.close()


def test_close_stops_workers_and_releases_gpio(tmp_path):
    handler, store, gpio = make_handler(tmp_path / "close.db")

    handler.close()

    assert gpio.stop_requested.is_set()
    assert gpio.closed is True
    store.close()
