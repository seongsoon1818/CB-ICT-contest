from __future__ import annotations

from threading import Event

from animalguard_embedded.gpio_adapter import CommandInterruptedError
from animalguard_embedded.settings import Settings


def valid_env(**overrides: str) -> dict[str, str]:
    values = {
        "MQTT_HOST": "127.0.0.1",
        "MQTT_PORT": "1883",
        "MQTT_USERNAME": "operator",
        "MQTT_PASSWORD": "fake-test-password",
        "MQTT_DEVICE_ID": "pi-001",
        "MQTT_KEEPALIVE_SECONDS": "60",
        "PROCESSED_COMMAND_DB": "./data/processed_commands.db",
        "MOTOR_IN1_PIN": "17",
        "MOTOR_IN2_PIN": "27",
        "MOTOR_SLEEP_PIN": "22",
        "SERVO_PIN": "19",
        "SPEAKER_PIN": "23",
        "SERVO_STEP_DEGREES": "5",
        "SERVO_MIN_ANGLE": "-60",
        "SERVO_MAX_ANGLE": "60",
        "MAX_MOTOR_DURATION_MS": "5000",
        "MAX_SOUND_DURATION_MS": "5000",
    }
    values.update(overrides)
    return values


def settings(**overrides: str) -> Settings:
    return Settings.from_mapping(valid_env(**overrides))


class FakeGPIO:
    def __init__(self, block_timed: bool = False) -> None:
        self.actions: list[tuple[str, int | None]] = []
        self.block_timed = block_timed
        self.started = Event()
        self.stop_requested = Event()
        self.closed = False
        self.current_angle = 0

    @property
    def execution_count(self) -> int:
        return len([action for action in self.actions if action[0] != "STOP_DETERRENT"])

    def rotate_left(self) -> None:
        self.current_angle -= 5
        self.actions.append(("ROTATE_CAMERA_LEFT", None))

    def rotate_right(self) -> None:
        self.current_angle += 5
        self.actions.append(("ROTATE_CAMERA_RIGHT", None))

    def sound_alert(self, duration_ms: int) -> None:
        self.actions.append(("SOUND_ALERT", duration_ms))
        self._wait_if_blocked()

    def deterrent_full(self, duration_ms: int) -> None:
        self.actions.append(("DETERRENT_FULL", duration_ms))
        self._wait_if_blocked()

    def stop_deterrent(self) -> None:
        self.actions.append(("STOP_DETERRENT", None))
        self.stop_requested.set()

    def close(self) -> None:
        self.closed = True

    def _wait_if_blocked(self) -> None:
        if not self.block_timed:
            return
        self.started.set()
        if self.stop_requested.wait(2):
            raise CommandInterruptedError("stopped by fake STOP")
        raise RuntimeError("fake timed action was not stopped")
