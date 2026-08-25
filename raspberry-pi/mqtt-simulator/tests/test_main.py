import json

import paho.mqtt.client as mqtt

from animalguard_mqtt.main import _publish_json


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
