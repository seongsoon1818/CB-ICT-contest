import json
from datetime import UTC, datetime, timedelta
from uuid import uuid4

from animalguard_mqtt.command_handler import CommandHandler
from animalguard_mqtt.dedup_store import DedupStore
from animalguard_mqtt.mock_gpio import MockGPIO


NOW = datetime(2026, 8, 25, 3, 20, tzinfo=UTC)


def command_payload(**overrides):
    payload = {
        "commandId": "cmd-001",
        "eventId": str(uuid4()),
        "deviceId": "pi-001",
        "command": "DETERRENT_LEVEL_1",
        "durationMs": 5000,
        "issuedAt": (NOW - timedelta(seconds=1)).isoformat(),
        "expiresAt": (NOW + timedelta(seconds=10)).isoformat(),
        "reason": "HIGH_RISK_MAGPIE",
    }
    payload.update(overrides)
    return json.dumps(payload)


def make_handler(db_path, gpio=None):
    store = DedupStore(db_path)
    return CommandHandler("pi-001", store, gpio or MockGPIO(), clock=lambda: NOW), store


def test_valid_command_publishes_acknowledged_then_executed(tmp_path):
    handler, store = make_handler(tmp_path / "commands.db")
    published = []

    handler.handle(command_payload(), published.append)

    assert [ack["status"] for ack in published] == ["ACKNOWLEDGED", "EXECUTED"]
    assert handler.gpio.execution_count == 1
    assert store.get("cmd-001").status == "EXECUTED"
    store.close()


def test_expired_command_does_not_execute_and_publishes_expired(tmp_path):
    handler, store = make_handler(tmp_path / "commands.db")
    published = []

    handler.handle(
        command_payload(expiresAt=(NOW - timedelta(milliseconds=1)).isoformat()),
        published.append,
    )

    assert [ack["status"] for ack in published] == ["EXPIRED"]
    assert handler.gpio.execution_count == 0
    assert store.get("cmd-001").status == "EXPIRED"
    store.close()


def test_unsupported_command_publishes_failed_without_execution(tmp_path):
    handler, store = make_handler(tmp_path / "commands.db")
    published = []

    handler.handle(command_payload(command="RAW_PWM"), published.append)

    assert [ack["status"] for ack in published] == ["FAILED"]
    assert handler.gpio.execution_count == 0
    assert store.get("cmd-001").status == "FAILED"
    store.close()


def test_duplicate_republishes_terminal_ack_without_execution(tmp_path):
    handler, store = make_handler(tmp_path / "commands.db")
    first, duplicate = [], []

    handler.handle(command_payload(), first.append)
    handler.handle(command_payload(), duplicate.append)

    assert handler.gpio.execution_count == 1
    assert [ack["status"] for ack in duplicate] == ["EXECUTED"]
    assert duplicate[0] == first[-1]
    store.close()


def test_duplicate_remains_deduplicated_after_sqlite_restart(tmp_path):
    db_path = tmp_path / "commands.db"
    first_handler, first_store = make_handler(db_path)
    first_handler.handle(command_payload(), lambda ack: None)
    first_store.close()

    second_handler, second_store = make_handler(db_path)
    published = []
    second_handler.handle(command_payload(), published.append)

    assert second_handler.gpio.execution_count == 0
    assert [ack["status"] for ack in published] == ["EXECUTED"]
    second_store.close()


def test_gpio_failure_publishes_and_stores_failed(tmp_path):
    class FailingGPIO:
        execution_count = 0

        def execute(self, command, duration_ms):
            raise RuntimeError("mock failure")

    handler, store = make_handler(tmp_path / "commands.db", FailingGPIO())
    published = []

    handler.handle(command_payload(), published.append)

    assert [ack["status"] for ack in published] == ["ACKNOWLEDGED", "FAILED"]
    assert store.get("cmd-001").status == "FAILED"
    store.close()


def test_invalid_json_without_identifiers_does_not_publish_ack(tmp_path):
    handler, store = make_handler(tmp_path / "commands.db")
    published = []

    handler.handle("not-json", published.append)

    assert published == []
    assert handler.gpio.execution_count == 0
    store.close()


def test_rejected_mqtt_envelope_publishes_failed_without_execution(tmp_path):
    handler, store = make_handler(tmp_path / "commands.db")
    published = []

    handler.reject(command_payload(), published.append, "qos=0 retain=False")

    assert [ack["status"] for ack in published] == ["FAILED"]
    assert handler.gpio.execution_count == 0
    assert store.get("cmd-001").status == "FAILED"
    store.close()
