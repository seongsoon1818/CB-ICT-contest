from __future__ import annotations

import os
from dataclasses import dataclass, field
from math import isfinite
from pathlib import Path
from typing import Mapping


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    username: str
    password: str = field(repr=False)
    device_id: str
    keepalive_seconds: int
    processed_command_db: Path
    motor_in1_pin: int
    motor_in2_pin: int
    motor_sleep_pin: int
    servo_pin: int
    speaker_pin: int
    servo_step_degrees: float
    servo_min_angle: float
    servo_max_angle: float
    max_motor_duration_ms: int
    max_sound_duration_ms: int

    @classmethod
    def from_env(cls) -> "Settings":
        return cls.from_mapping(os.environ)

    @classmethod
    def from_mapping(cls, values: Mapping[str, str]) -> "Settings":
        host = _required_text(values, "MQTT_HOST")
        device_id = _required_text(values, "MQTT_DEVICE_ID")
        database_path = Path(_required_text(values, "PROCESSED_COMMAND_DB"))
        username = values.get("MQTT_USERNAME", "").strip()
        password = values.get("MQTT_PASSWORD", "")
        if password and not username:
            raise ValueError("MQTT_USERNAME is required when MQTT_PASSWORD is set")

        port = _positive_int(values, "MQTT_PORT")
        if port > 65535:
            raise ValueError("MQTT_PORT must be between 1 and 65535")

        pin_names = (
            "MOTOR_IN1_PIN",
            "MOTOR_IN2_PIN",
            "MOTOR_SLEEP_PIN",
            "SERVO_PIN",
            "SPEAKER_PIN",
        )
        pins = {name: _non_negative_int(values, name) for name in pin_names}
        if len(set(pins.values())) != len(pins):
            raise ValueError("GPIO pin settings must be unique")

        servo_step = _positive_float(values, "SERVO_STEP_DEGREES")
        servo_min = _float(values, "SERVO_MIN_ANGLE")
        servo_max = _float(values, "SERVO_MAX_ANGLE")
        if not servo_min < 0 < servo_max:
            raise ValueError("SERVO_MIN_ANGLE < 0 < SERVO_MAX_ANGLE is required")
        if servo_step > servo_max - servo_min:
            raise ValueError("SERVO_STEP_DEGREES must not exceed the servo range")

        return cls(
            host=host,
            port=port,
            username=username,
            password=password,
            device_id=device_id,
            keepalive_seconds=_positive_int(values, "MQTT_KEEPALIVE_SECONDS"),
            processed_command_db=database_path,
            motor_in1_pin=pins["MOTOR_IN1_PIN"],
            motor_in2_pin=pins["MOTOR_IN2_PIN"],
            motor_sleep_pin=pins["MOTOR_SLEEP_PIN"],
            servo_pin=pins["SERVO_PIN"],
            speaker_pin=pins["SPEAKER_PIN"],
            servo_step_degrees=servo_step,
            servo_min_angle=servo_min,
            servo_max_angle=servo_max,
            max_motor_duration_ms=_positive_int(values, "MAX_MOTOR_DURATION_MS"),
            max_sound_duration_ms=_positive_int(values, "MAX_SOUND_DURATION_MS"),
        )


def _required_text(values: Mapping[str, str], name: str) -> str:
    value = values.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} must not be empty")
    return value


def _positive_int(values: Mapping[str, str], name: str) -> int:
    value = _integer(values, name)
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _non_negative_int(values: Mapping[str, str], name: str) -> int:
    value = _integer(values, name)
    if value < 0:
        raise ValueError(f"{name} must not be negative")
    return value


def _integer(values: Mapping[str, str], name: str) -> int:
    raw_value = _required_text(values, name)
    try:
        return int(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


def _positive_float(values: Mapping[str, str], name: str) -> float:
    value = _float(values, name)
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value


def _float(values: Mapping[str, str], name: str) -> float:
    raw_value = _required_text(values, name)
    try:
        value = float(raw_value)
    except ValueError as exc:
        raise ValueError(f"{name} must be numeric") from exc
    if not isfinite(value):
        raise ValueError(f"{name} must be finite")
    return value
