import json
from types import SimpleNamespace

import paho.mqtt.client as mqtt
import pytest

from animalguard_embedded import main as main_module
from animalguard_embedded.main import _build_client, _publish_json
from tests.helpers import settings


class PublishInfo:
    rc = mqtt.MQTT_ERR_SUCCESS

    def __init__(self):
        self.waited = False

    def wait_for_publish(self, timeout):
        assert timeout == 5
        self.waited = True


class FakeClient:
    def __init__(self, **options):
        self.options = options
        self.credentials = None
        self.will = None
        self.subscriptions = []
        self.published = []
        self.connected = True
        self.loop_started = False
        self.loop_stopped = False
        self.disconnected = False
        self.on_connect = None
        self.on_message = None

    def username_pw_set(self, username, password):
        self.credentials = (username, password)

    def will_set(self, topic, payload, qos, retain):
        self.will = (topic, json.loads(payload), qos, retain)

    def subscribe(self, topic, qos):
        self.subscriptions.append((topic, qos))
        return mqtt.MQTT_ERR_SUCCESS, 1

    def publish(self, topic, payload, qos, retain):
        info = PublishInfo()
        self.published.append((topic, json.loads(payload), qos, retain, info))
        return info

    def connect(self, host, port, keepalive):
        self.connect_args = (host, port, keepalive)
        return mqtt.MQTT_ERR_SUCCESS

    def loop_start(self):
        self.loop_started = True

    def loop_stop(self):
        self.loop_stopped = True

    def is_connected(self):
        return self.connected

    def disconnect(self):
        self.disconnected = True
        self.connected = False


class FakeHandler:
    def __init__(self, *args):
        self.args = args
        self.handled = []
        self.rejected = []
        self.closed = False

    def handle(self, payload, publish_ack):
        self.handled.append(payload)

    def reject(self, payload, publish_ack, reason):
        self.rejected.append((payload, reason))

    def close(self):
        self.closed = True


def test_client_configures_credentials_lwt_and_connection_callback(monkeypatch):
    monkeypatch.setattr(main_module.mqtt, "Client", FakeClient)
    handler = FakeHandler()

    client = _build_client(settings(), handler)

    assert client.options["client_id"] == "animalguard-embedded-pi-001"
    assert client.credentials == ("operator", "fake-test-password")
    topic, payload, qos, retain = client.will
    assert topic == "animalguard/devices/pi-001/status"
    assert payload["status"] == "OFFLINE"
    assert payload["reportedAt"].endswith("Z")
    assert payload["firmwareVersion"] == "animalguard-embedded-v1"
    assert (qos, retain) == (1, True)

    client.on_connect(client, None, None, 0, None)

    assert client.subscriptions == [("animalguard/devices/pi-001/commands", 1)]
    online = client.published[-1]
    assert online[0] == "animalguard/devices/pi-001/status"
    assert online[1]["status"] == "ONLINE"
    assert online[2:4] == (1, True)


def test_message_callback_accepts_only_exact_topic_qos_and_retain(monkeypatch):
    monkeypatch.setattr(main_module.mqtt, "Client", FakeClient)
    handler = FakeHandler()
    client = _build_client(settings(), handler)
    valid = SimpleNamespace(
        payload=b"{}",
        topic="animalguard/devices/pi-001/commands",
        qos=1,
        retain=False,
    )

    client.on_message(client, None, valid)
    assert handler.handled == [b"{}"]

    for message in [
        SimpleNamespace(payload=b"{}", topic="birdguard/devices/pi-001/commands", qos=1, retain=False),
        SimpleNamespace(payload=b"{}", topic=valid.topic, qos=0, retain=False),
        SimpleNamespace(payload=b"{}", topic=valid.topic, qos=1, retain=True),
    ]:
        client.on_message(client, None, message)

    assert len(handler.rejected) == 3


def test_ack_publish_uses_qos_one_and_never_retains():
    client = FakeClient()

    _publish_json(
        client,
        "animalguard/devices/pi-001/acks",
        {"commandId": "command-001", "status": "EXECUTED"},
        retain=False,
    )

    assert client.published[0][:4] == (
        "animalguard/devices/pi-001/acks",
        {"commandId": "command-001", "status": "EXECUTED"},
        1,
        False,
    )


def test_main_closes_gpio_store_mqtt_and_publishes_offline(monkeypatch):
    configured = settings()
    gpio = SimpleNamespace()
    store = SimpleNamespace(closed=False)
    store.close = lambda: setattr(store, "closed", True)
    handler = FakeHandler()
    client = FakeClient()

    class StopEvent:
        def wait(self, timeout):
            assert timeout == 1
            return True

        def set(self):
            pass

    monkeypatch.setattr(
        main_module.Settings,
        "from_env",
        classmethod(lambda cls: configured),
    )
    monkeypatch.setattr(main_module, "GpioZeroAdapter", lambda current: gpio)
    monkeypatch.setattr(main_module, "DedupStore", lambda path: store)
    monkeypatch.setattr(main_module, "CommandHandler", lambda *args: handler)
    monkeypatch.setattr(main_module, "_build_client", lambda current, current_handler: client)
    monkeypatch.setattr(main_module, "Event", StopEvent)
    monkeypatch.setattr(main_module.signal, "signal", lambda signum, callback: None)

    assert main_module.main() == 0

    assert client.connect_args == ("127.0.0.1", 1883, 60)
    assert client.loop_started is True
    assert handler.closed is True
    assert client.published[-1][1]["status"] == "OFFLINE"
    assert client.published[-1][4].waited is True
    assert client.disconnected is True
    assert client.loop_stopped is True
    assert store.closed is True


def test_main_closes_gpio_when_store_initialization_fails(monkeypatch):
    gpio = SimpleNamespace(closed=False)
    gpio.close = lambda: setattr(gpio, "closed", True)

    monkeypatch.setattr(
        main_module.Settings,
        "from_env",
        classmethod(lambda cls: settings()),
    )
    monkeypatch.setattr(main_module, "GpioZeroAdapter", lambda current: gpio)

    def fail_store(path):
        raise RuntimeError(f"fake store failure: {path}")

    monkeypatch.setattr(main_module, "DedupStore", fail_store)

    with pytest.raises(RuntimeError, match="fake store failure"):
        main_module.main()

    assert gpio.closed is True
