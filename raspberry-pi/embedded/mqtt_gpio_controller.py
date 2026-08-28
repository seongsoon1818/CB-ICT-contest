#!/usr/bin/env python3
"""Single Paho MQTT controller for two motors, a speaker, and a camera servo."""

from __future__ import annotations

import json
import logging
import os
import signal
import sqlite3
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import paho.mqtt.client as mqtt
from gpiozero import Motor, OutputDevice, Servo


MQTT_HOST = os.getenv("MQTT_HOST", "10.112.89.131")
MQTT_PORT = int(os.getenv("MQTT_PORT", "1883"))
MQTT_USERNAME = os.getenv("MQTT_USERNAME", "animalguard-pi-001")
MQTT_PASSWORD = os.getenv("MQTT_PASSWORD", "")
DEVICE_ID = os.getenv("MQTT_DEVICE_ID", "pi-001")
MQTT_KEEPALIVE_SECONDS = 60

MOTOR_A_IN1_PIN = 17
MOTOR_A_IN2_PIN = 27
MOTOR_B_IN1_PIN = 23
MOTOR_B_IN2_PIN = 24
MOTOR_STANDBY_PIN = 22
SPEAKER_PIN: int | None = None
SERVO_PIN = 19

SERVO_LEFT_VALUE = -1.0
SERVO_RIGHT_VALUE = 1.0
SERVO_STOP_VALUE = 0.0
SERVO_RUN_SECONDS = 0.1
MAX_MOTOR_DURATION_MS = 60_000
MAX_SOUND_DURATION_MS = 60_000

COMMAND_TOPIC = f"animalguard/devices/{DEVICE_ID}/commands"
ACK_TOPIC = f"animalguard/devices/{DEVICE_ID}/acks"
STATUS_TOPIC = f"animalguard/devices/{DEVICE_ID}/status"
DATABASE_PATH = Path("data/mqtt_gpio_controller.db")

running = True
motor_a: Motor | None = None
motor_b: Motor | None = None
standby_pin: OutputDevice | None = None
speaker: OutputDevice | None = None
servo: Servo | None = None
gpio_lock = threading.Lock()
timed_action_lock = threading.Lock()
stop_event = threading.Event()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def validate_mqtt_settings() -> None:
    if not MQTT_HOST:
        raise ValueError("MQTT_HOST must not be empty")
    if not 1 <= MQTT_PORT <= 65535:
        raise ValueError("MQTT_PORT must be between 1 and 65535")
    if not MQTT_USERNAME:
        raise ValueError("MQTT_USERNAME must not be empty")
    if not MQTT_PASSWORD:
        raise ValueError("MQTT_PASSWORD must be set in the process environment")
    if not DEVICE_ID:
        raise ValueError("MQTT_DEVICE_ID must not be empty")


def build_ack(command_id: str, status: str) -> dict[str, str]:
    timestamp_key = {
        "ACKNOWLEDGED": "acknowledgedAt",
        "EXECUTED": "executedAt",
        "FAILED": "failedAt",
        "EXPIRED": "expiredAt",
    }[status]
    return {
        "commandId": command_id,
        "deviceId": DEVICE_ID,
        "status": status,
        timestamp_key: now_iso(),
    }


def status_payload(status: str) -> dict[str, str]:
    return {
        "deviceId": DEVICE_ID,
        "status": status,
        "reportedAt": now_iso(),
        "firmwareVersion": "mqtt-gpio-controller-v1",
    }


def initialize_database() -> None:
    DATABASE_PATH.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(DATABASE_PATH) as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS command_acks (
                command_id TEXT PRIMARY KEY,
                ack_json TEXT NOT NULL
            )
            """
        )


def load_ack(command_id: str) -> dict[str, Any] | None:
    with sqlite3.connect(DATABASE_PATH) as connection:
        row = connection.execute(
            "SELECT ack_json FROM command_acks WHERE command_id = ?",
            (command_id,),
        ).fetchone()
    return None if row is None else json.loads(row[0])


def save_ack(ack: dict[str, Any]) -> None:
    with sqlite3.connect(DATABASE_PATH) as connection:
        connection.execute(
            """
            INSERT INTO command_acks (command_id, ack_json)
            VALUES (?, ?)
            ON CONFLICT(command_id) DO UPDATE SET ack_json = excluded.ack_json
            """,
            (ack["commandId"], json.dumps(ack, separators=(",", ":"))),
        )


def publish_json(
    client: mqtt.Client,
    topic: str,
    payload: dict[str, Any],
    retain: bool,
) -> None:
    info = client.publish(
        topic,
        json.dumps(payload, separators=(",", ":")),
        qos=1,
        retain=retain,
    )
    if info.rc != mqtt.MQTT_ERR_SUCCESS:
        raise RuntimeError(f"MQTT publish failed: topic={topic} rc={info.rc}")


def publish_ack(client: mqtt.Client, ack: dict[str, Any]) -> None:
    save_ack(ack)
    publish_json(client, ACK_TOPIC, ack, retain=False)


def initialize_hardware() -> None:
    global motor_a, motor_b, standby_pin, speaker, servo
    configured_pins = [
        MOTOR_A_IN1_PIN,
        MOTOR_A_IN2_PIN,
        MOTOR_B_IN1_PIN,
        MOTOR_B_IN2_PIN,
        MOTOR_STANDBY_PIN,
    ]
    configured_pins.extend(
        pin for pin in (SPEAKER_PIN, SERVO_PIN) if pin is not None
    )
    if len(configured_pins) != len(set(configured_pins)):
        raise ValueError("Configured GPIO pins must be unique")

    motor_a = Motor(forward=MOTOR_A_IN1_PIN, backward=MOTOR_A_IN2_PIN, pwm=False)
    motor_b = Motor(forward=MOTOR_B_IN1_PIN, backward=MOTOR_B_IN2_PIN, pwm=False)
    standby_pin = OutputDevice(MOTOR_STANDBY_PIN, initial_value=False)
    speaker = (
        OutputDevice(SPEAKER_PIN, initial_value=False)
        if SPEAKER_PIN is not None
        else None
    )
    servo = (
        Servo(
            SERVO_PIN,
            initial_value=SERVO_STOP_VALUE,
        )
        if SERVO_PIN is not None
        else None
    )


def stop_deterrent_outputs() -> None:
    with gpio_lock:
        if motor_a is not None:
            motor_a.stop()
        if motor_b is not None:
            motor_b.stop()
        if speaker is not None:
            speaker.off()
        if standby_pin is not None:
            standby_pin.off()


def close_hardware() -> None:
    global motor_a, motor_b, standby_pin, speaker, servo
    stop_deterrent_outputs()
    if servo is not None:
        servo.value = SERVO_STOP_VALUE
    for device in (servo, speaker, motor_a, motor_b, standby_pin):
        if device is not None:
            device.close()
    motor_a = None
    motor_b = None
    standby_pin = None
    speaker = None
    servo = None


def start_sound() -> None:
    with gpio_lock:
        if speaker is None:
            raise RuntimeError("SPEAKER_PIN is not configured")
        speaker.on()


def start_deterrent_full() -> None:
    with gpio_lock:
        if motor_a is None or motor_b is None or standby_pin is None:
            raise RuntimeError("Motor GPIO is not initialized")
        standby_pin.on()
        time.sleep(0.01)
        motor_a.forward()
        motor_b.forward()
        if speaker is not None:
            speaker.on()
        else:
            logging.warning("DETERRENT_FULL is running without a configured speaker")


def rotate_camera(direction_value: float) -> None:
    with gpio_lock:
        if servo is None:
            raise RuntimeError("SERVO_PIN is not configured")
        servo.value = direction_value
    try:
        time.sleep(SERVO_RUN_SECONDS)
    finally:
        with gpio_lock:
            if servo is not None:
                servo.value = SERVO_STOP_VALUE


def run_timed_action(
    client: mqtt.Client,
    command_id: str,
    command: str,
    duration_ms: int,
) -> None:
    try:
        if command == "SOUND_ALERT":
            start_sound()
        else:
            start_deterrent_full()
        interrupted = stop_event.wait(duration_ms / 1000)
        publish_ack(
            client,
            build_ack(command_id, "FAILED" if interrupted else "EXECUTED"),
        )
    except Exception:
        logging.exception("GPIO action failed: commandId=%s", command_id)
        publish_ack(client, build_ack(command_id, "FAILED"))
    finally:
        stop_deterrent_outputs()
        timed_action_lock.release()


def parse_expiry(raw_value: Any) -> datetime:
    if not isinstance(raw_value, str):
        raise ValueError("expiresAt must be an ISO-8601 string")
    expires_at = datetime.fromisoformat(raw_value.replace("Z", "+00:00"))
    if expires_at.tzinfo is None or expires_at.utcoffset() is None:
        raise ValueError("expiresAt must include a timezone")
    return expires_at


def start_timed_command(
    client: mqtt.Client,
    command_id: str,
    command: str,
    duration_ms: Any,
) -> None:
    if isinstance(duration_ms, bool) or not isinstance(duration_ms, int):
        raise ValueError(f"{command} durationMs must be an integer")
    maximum_duration = (
        MAX_SOUND_DURATION_MS if command == "SOUND_ALERT" else MAX_MOTOR_DURATION_MS
    )
    if not 0 < duration_ms <= maximum_duration:
        raise ValueError(f"durationMs must be between 1 and {maximum_duration}")
    if not timed_action_lock.acquire(blocking=False):
        raise RuntimeError("Another timed GPIO action is already active")

    try:
        stop_event.clear()
        publish_ack(client, build_ack(command_id, "ACKNOWLEDGED"))
        threading.Thread(
            target=run_timed_action,
            args=(client, command_id, command, duration_ms),
            daemon=True,
            name=f"gpio-{command_id}",
        ).start()
    except Exception:
        timed_action_lock.release()
        raise


def handle_command(client: mqtt.Client, raw_payload: bytes) -> None:
    try:
        payload = json.loads(raw_payload.decode("utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("payload must be a JSON object")
        command_id = payload["commandId"]
        if not isinstance(command_id, str) or not command_id:
            raise ValueError("commandId must be a non-empty string")
        if payload["deviceId"] != DEVICE_ID:
            logging.warning("Ignored command for another device")
            return
    except (UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        logging.warning("Ignored invalid MQTT command: %s", error)
        return

    previous_ack = load_ack(command_id)
    if previous_ack is not None:
        publish_json(client, ACK_TOPIC, previous_ack, retain=False)
        return

    try:
        if datetime.now(timezone.utc) >= parse_expiry(payload["expiresAt"]):
            publish_ack(client, build_ack(command_id, "EXPIRED"))
            return

        command = payload["command"]
        duration_ms = payload.get("durationMs")
        if command == "STOP_DETERRENT":
            if duration_ms is not None:
                raise ValueError("STOP_DETERRENT durationMs must be null")
            stop_event.set()
            stop_deterrent_outputs()
            publish_ack(client, build_ack(command_id, "EXECUTED"))
            return
        if command == "ROTATE_CAMERA_LEFT":
            if duration_ms is not None:
                raise ValueError("ROTATE_CAMERA_LEFT durationMs must be null")
            rotate_camera(SERVO_LEFT_VALUE)
            publish_ack(client, build_ack(command_id, "EXECUTED"))
            return
        if command == "ROTATE_CAMERA_RIGHT":
            if duration_ms is not None:
                raise ValueError("ROTATE_CAMERA_RIGHT durationMs must be null")
            rotate_camera(SERVO_RIGHT_VALUE)
            publish_ack(client, build_ack(command_id, "EXECUTED"))
            return
        if command in {"SOUND_ALERT", "DETERRENT_FULL"}:
            start_timed_command(client, command_id, command, duration_ms)
            return
        raise ValueError(f"Unsupported command: {command}")
    except (KeyError, TypeError, ValueError, RuntimeError) as error:
        logging.warning("Rejected commandId=%s: %s", command_id, error)
        publish_ack(client, build_ack(command_id, "FAILED"))


def request_stop(signum: int, frame: object) -> None:
    global running
    del frame
    logging.info("Shutdown signal received: %s", signum)
    running = False
    stop_event.set()


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    client: mqtt.Client | None = None
    loop_started = False
    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        validate_mqtt_settings()
        initialize_database()
        initialize_hardware()
        client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"gpio-controller-{DEVICE_ID}",
            protocol=mqtt.MQTTv311,
        )
        client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)
        client.will_set(
            STATUS_TOPIC,
            json.dumps(status_payload("OFFLINE"), separators=(",", ":")),
            qos=1,
            retain=True,
        )

        def on_connect(
            connected_client: mqtt.Client,
            userdata: object,
            connect_flags: object,
            reason_code: object,
            properties: object,
        ) -> None:
            del userdata, connect_flags, properties
            if reason_code != 0:
                logging.error("MQTT connection failed: %s", reason_code)
                return
            connected_client.subscribe(COMMAND_TOPIC, qos=1)
            publish_json(
                connected_client,
                STATUS_TOPIC,
                status_payload("ONLINE"),
                retain=True,
            )
            logging.info("Subscribed to %s", COMMAND_TOPIC)

        def on_message(
            connected_client: mqtt.Client,
            userdata: object,
            message: mqtt.MQTTMessage,
        ) -> None:
            del userdata
            if message.topic != COMMAND_TOPIC or message.qos != 1 or message.retain:
                logging.warning("Ignored invalid MQTT envelope")
                return
            handle_command(connected_client, message.payload)

        client.on_connect = on_connect
        client.on_message = on_message
        client.connect(MQTT_HOST, MQTT_PORT, MQTT_KEEPALIVE_SECONDS)
        client.loop_start()
        loop_started = True
        while running:
            time.sleep(0.2)
    finally:
        stop_event.set()
        stop_deterrent_outputs()
        if client is not None and client.is_connected():
            try:
                publish_json(client, STATUS_TOPIC, status_payload("OFFLINE"), retain=True)
                client.disconnect()
            except RuntimeError:
                logging.exception("Failed to publish OFFLINE status")
        if client is not None and loop_started:
            client.loop_stop()
        close_hardware()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
