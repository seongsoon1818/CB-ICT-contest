from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    device_id: str
    keepalive_seconds: int
    processed_command_db: Path
    status_interval_seconds: int

    @classmethod
    def from_env(cls) -> "Settings":
        return cls.from_mapping(os.environ)

    @classmethod
    def from_mapping(cls, values: Mapping[str, str]) -> "Settings":
        host = values.get("MQTT_HOST", "127.0.0.1").strip()
        device_id = values.get("MQTT_DEVICE_ID", "pi-001").strip()
        db_value = values.get(
            "PROCESSED_COMMAND_DB", "./data/processed_commands.db"
        ).strip()

        if not host:
            raise ValueError("MQTT_HOST must not be empty")
        if not device_id:
            raise ValueError("MQTT_DEVICE_ID must not be empty")
        if not db_value:
            raise ValueError("PROCESSED_COMMAND_DB must not be empty")

        port = _positive_int(values, "MQTT_PORT", 1883)
        if port > 65535:
            raise ValueError("MQTT_PORT must be between 1 and 65535")

        return cls(
            host=host,
            port=port,
            device_id=device_id,
            keepalive_seconds=_positive_int(
                values, "MQTT_KEEPALIVE_SECONDS", 30
            ),
            processed_command_db=Path(db_value),
            status_interval_seconds=_positive_int(
                values, "STATUS_INTERVAL_SECONDS", 30
            ),
        )


def _positive_int(values: Mapping[str, str], name: str, default: int) -> int:
    raw_value = values.get(name, str(default))
    try:
        value = int(raw_value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return value
