import json
from types import SimpleNamespace

import paho.mqtt.client as mqtt
import pytest

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
