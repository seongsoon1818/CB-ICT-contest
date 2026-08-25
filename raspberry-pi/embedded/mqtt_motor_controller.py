#!/usr/bin/env python3
from __future__ import annotations

import json
import logging
import threading
import time
from datetime import datetime, timezone

import paho.mqtt.client as mqtt
from gpiozero import Motor, OutputDevice


# Network and device identity
MQTT_HOST = "192.168.137.10"
MQTT_PORT = 1883
MQTT_USERNAME = ""
MQTT_PASSWORD = ""
DEVICE_ID = "piseong"

# GPIO uses BCM numbers, not physical pin numbers.
MOTOR_IN1_PIN = 17
MOTOR_IN2_PIN = 27
MOTOR_SLEEP_PIN = 22
MAX_MOTOR_DURATION_MS = 5000

COMMAND_TOPIC = f"birdguard/devices/{DEVICE_ID}/commands"
ACK_TOPIC = f"birdguard/devices/{DEVICE_ID}/acks"
STATUS_TOPIC = f"birdguard/devices/{DEVICE_ID}/status"

motor = Motor(
    forward=MOTOR_IN1_PIN,
    backward=MOTOR_IN2_PIN,
    pwm=False,
)

# nSLEEP 핀이 모듈에 노출된 경우에만 이 객체를 사용한다.
sleep_pin = OutputDevice(MOTOR_SLEEP_PIN, initial_value=False)

processed_status: dict[str, str] = {}
motor_busy = threading.Lock()
stop_requested = threading.Event()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def status_payload(status: str) -> str:
    return json.dumps(
        {
            "deviceId": DEVICE_ID,
            "status": status,
            "reportedAt": now_iso(),
            "firmwareVersion": "pi-motor-v1",
        }
    )


def publish_ack(
    client: mqtt.Client,
    command_id: str,
    status: str,
    error: str | None = None,
) -> None:
    payload: dict[str, str] = {
        "commandId": command_id,
        "deviceId": DEVICE_ID,
        "status": status,
        "executedAt": now_iso(),
    }
    if error is not None:
        payload["error"] = error

    processed_status[command_id] = status
    client.publish(ACK_TOPIC, json.dumps(payload), qos=1, retain=False)


def is_expired(expires_at: str) -> bool:
    expires = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
    return datetime.now(timezone.utc) >= expires


def run_motor(
    client: mqtt.Client,
    command_id: str,
    duration_ms: int,
) -> None:
    try:
        stop_requested.clear()
        sleep_pin.on()
        time.sleep(0.01)

        motor.forward()
        stopped = stop_requested.wait(duration_ms / 1000)
        motor.stop()

        if stopped:
            publish_ack(client, command_id, "FAILED", "stopped_by_stop_command")
        else:
            publish_ack(client, command_id, "EXECUTED")
    except Exception as error:
        motor.stop()
        publish_ack(client, command_id, "FAILED", str(error))
    finally:
        motor_busy.release()


def on_connect(
    client: mqtt.Client,
    userdata: object,
    connect_flags: object,
    reason_code: object,
    properties: object,
) -> None:
    del userdata, connect_flags, properties
    if reason_code != 0:
        logging.error("MQTT connection failed: %s", reason_code)
        return

    client.subscribe(COMMAND_TOPIC, qos=1)
    client.publish(
        STATUS_TOPIC,
        status_payload("ONLINE"),
        qos=1,
        retain=False,
    )
    logging.info("Subscribed to %s", COMMAND_TOPIC)


def on_message(
    client: mqtt.Client,
    userdata: object,
    message: mqtt.MQTTMessage,
) -> None:
    del userdata
    try:
        payload = json.loads(message.payload.decode("utf-8"))
        command_id = str(payload["commandId"])
        command = str(payload["command"])

        if payload["deviceId"] != DEVICE_ID:
            logging.warning("Ignored command for another device")
            return

        # QoS 1 duplicate: repeat the most recently known result without motion.
        if command_id in processed_status:
            publish_ack(client, command_id, processed_status[command_id])
            return

        if is_expired(str(payload["expiresAt"])):
            publish_ack(client, command_id, "EXPIRED")
            return

        if command == "STOP_DETERRENT":
            stop_requested.set()
            motor.stop()
            publish_ack(client, command_id, "EXECUTED")
            return

        if command not in {
            "DETERRENT_LEVEL_1",
            "DETERRENT_LEVEL_2",
            "DETERRENT_LEVEL_3",
        }:
            publish_ack(client, command_id, "FAILED", "unsupported_command")
            return

        duration_ms = int(payload["durationMs"])
        if not 0 < duration_ms <= MAX_MOTOR_DURATION_MS:
            publish_ack(client, command_id, "FAILED", "invalid_duration")
            return

        # A second motor movement is rejected until the active one completes.
        if not motor_busy.acquire(blocking=False):
            publish_ack(client, command_id, "FAILED", "motor_busy")
            return

        publish_ack(client, command_id, "ACKNOWLEDGED")
        threading.Thread(
            target=run_motor,
            args=(client, command_id, duration_ms),
            daemon=True,
        ).start()

    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as error:
        logging.warning("Invalid MQTT command: %s", error)


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=DEVICE_ID,
        protocol=mqtt.MQTTv311,
    )
    if MQTT_USERNAME:
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

    client.will_set(
        STATUS_TOPIC,
        status_payload("OFFLINE"),
        qos=1,
        retain=False,
    )
    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=60)

    try:
        client.loop_forever()
    finally:
        motor.stop()
        sleep_pin.off()
        motor.close()
        sleep_pin.close()


if __name__ == "__main__":
    main()