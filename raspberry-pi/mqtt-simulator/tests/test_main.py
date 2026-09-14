import json
from types import SimpleNamespace

import paho.mqtt.client as mqtt
import pytest

from animalguard_mqtt import main as main_module
from animalguard_mqtt.main import _build_client, _publish_json
from animalguard_mqtt.settings import Settings


def test_publish_json_uses_qos_one_and_requested_retain_policy():
    class PublishInfo:
        rc = mqtt.MQTT_ERR_SUCCESS

    class Client:
        def __init__(self):
            self.calls = []

        def publish(self, topic, payload, qos, retain):
            self.calls.append((topic, json.loads(payload), qos, retain))
            return PublishInfo()

    client = Client()
    payload = {"deviceId": "pi-001", "status": "ONLINE"}

    _publish_json(client, "animalguard/devices/pi-001/status", payload, True)

    assert client.calls == [
        ("animalguard/devices/pi-001/status", payload, 1, True)
    ]


@pytest.mark.parametrize(
    ("qos", "retain"),
    [
        (0, False),
        (1, True),
    ],
)
def test_invalid_command_envelope_is_rejected(qos, retain, tmp_path):
    class Handler:
        def __init__(self):
            self.handled = []
            self.rejected = []

        def handle(self, payload, publish_ack):
            self.handled.append(payload)

        def reject(self, payload, publish_ack, reason):
            self.rejected.append((payload, reason))

    settings = Settings(
        host="127.0.0.1",
        port=1883,
        device_id="pi-001",
        keepalive_seconds=30,
        processed_command_db=tmp_path / "commands.db",
        status_interval_seconds=30,
    )
    handler = Handler()
    client = _build_client(settings, handler)
    message = SimpleNamespace(
        payload=b"{}",
        topic="animalguard/devices/pi-001/commands",
        qos=qos,
        retain=retain,
        dup=False,
    )

    client.on_message(client, None, message)

    assert handler.handled == []
    assert handler.rejected == [(b"{}", f"qos={qos} retain={retain}")]


def test_client_sets_retained_offline_last_will(monkeypatch, tmp_path):
    class Client:
        def __init__(self, **kwargs):
            self.options = kwargs
            self.will = None

        def will_set(self, topic, payload, qos, retain):
            self.will = (topic, json.loads(payload), qos, retain)

    settings = Settings(
        host="127.0.0.1",
        port=1883,
        device_id="pi-001",
        keepalive_seconds=30,
        processed_command_db=tmp_path / "commands.db",
        status_interval_seconds=30,
    )
    monkeypatch.setattr(main_module.mqtt, "Client", Client)

    client = _build_client(settings, object())

    topic, payload, qos, retain = client.will
    assert topic == "animalguard/devices/pi-001/status"
    assert payload["deviceId"] == "pi-001"
    assert payload["status"] == "OFFLINE"
    assert payload["reportedAt"].endswith("Z")
    assert payload["firmwareVersion"] == "mqtt-simulator-v1"
    assert qos == 1
    assert retain is True


def test_main_survives_transient_heartbeat_publish_failure(monkeypatch, tmp_path):
    class StopEvent:
        def __init__(self):
            self.wait_count = 0

        def wait(self, timeout):
            assert timeout == 30
            self.wait_count += 1
            return self.wait_count > 1

        def set(self):
            pass

    class PublishInfo:
        rc = mqtt.MQTT_ERR_NO_CONN

    class Client:
        def __init__(self):
            self.loop_stopped = False

        def connect(self, host, port, keepalive):
            return mqtt.MQTT_ERR_SUCCESS

        def loop_start(self):
            pass

        def publish(self, topic, payload, qos, retain):
            return PublishInfo()

        def is_connected(self):
            return False

        def loop_stop(self):
            self.loop_stopped = True

    settings = Settings(
        host="127.0.0.1",
        port=1883,
        device_id="pi-001",
        keepalive_seconds=30,
        processed_command_db=tmp_path / "commands.db",
        status_interval_seconds=30,
    )
    stop_event = StopEvent()
    client = Client()
    monkeypatch.setattr(
        main_module.Settings,
        "from_env",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(main_module, "Event", lambda: stop_event)
    monkeypatch.setattr(
        main_module, "_build_client", lambda current_settings, handler: client
    )
    monkeypatch.setattr(main_module.signal, "signal", lambda signum, handler: None)

    assert main_module.main() == 0
    assert stop_event.wait_count == 2
    assert client.loop_stopped is True


def test_main_cleans_up_when_offline_publish_wait_fails(monkeypatch, tmp_path):
    class StopEvent:
        def wait(self, timeout):
            return True

        def set(self):
            pass

    class PublishInfo:
        rc = mqtt.MQTT_ERR_SUCCESS

        def wait_for_publish(self, timeout):
            raise RuntimeError("connection lost while waiting")

    class Client:
        def __init__(self):
            self.disconnected = False
            self.loop_stopped = False

        def connect(self, host, port, keepalive):
            return mqtt.MQTT_ERR_SUCCESS

        def loop_start(self):
            pass

        def publish(self, topic, payload, qos, retain):
            return PublishInfo()

        def is_connected(self):
            return True

        def disconnect(self):
            self.disconnected = True

        def loop_stop(self):
            self.loop_stopped = True

    class Store:
        def __init__(self):
            self.closed = False

        def close(self):
            self.closed = True

    settings = Settings(
        host="127.0.0.1",
        port=1883,
        device_id="pi-001",
        keepalive_seconds=30,
        processed_command_db=tmp_path / "commands.db",
        status_interval_seconds=30,
    )
    client = Client()
    store = Store()
    monkeypatch.setattr(
        main_module.Settings,
        "from_env",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(main_module, "Event", StopEvent)
    monkeypatch.setattr(main_module, "DedupStore", lambda path: store)
    monkeypatch.setattr(
        main_module, "_build_client", lambda current_settings, handler: client
    )
    monkeypatch.setattr(main_module.signal, "signal", lambda signum, handler: None)

    assert main_module.main() == 0
    assert client.disconnected is True
    assert client.loop_stopped is True
    assert store.closed is True
