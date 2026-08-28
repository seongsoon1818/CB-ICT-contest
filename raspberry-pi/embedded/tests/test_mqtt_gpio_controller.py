from __future__ import annotations

import json
from types import SimpleNamespace

import pytest

import mqtt_gpio_controller as controller


class FakeServo:
    def __init__(self) -> None:
        self.angles: list[float] = []
        self.detach_count = 0
        self.closed = False

    @property
    def angle(self) -> float | None:
        return self.angles[-1] if self.angles else None

    @angle.setter
    def angle(self, angle: float) -> None:
        self.angles.append(angle)

    def detach(self) -> None:
        self.detach_count += 1

    def close(self) -> None:
        self.closed = True


def test_status_identifies_relative_servo_firmware():
    assert controller.status_payload("ONLINE")["firmwareVersion"] == (
        "mqtt-gpio-controller-v2"
    )


def test_hardware_initialization_does_not_command_the_servo_to_center(monkeypatch):
    servo_settings: list[dict[str, float | None]] = []

    monkeypatch.setattr(
        controller,
        "Motor",
        lambda **kwargs: SimpleNamespace(close=lambda: None),
    )
    monkeypatch.setattr(
        controller,
        "OutputDevice",
        lambda *args, **kwargs: SimpleNamespace(close=lambda: None),
    )

    def make_servo(pin, **settings):
        del pin
        servo_settings.append(settings)
        return FakeServo()

    monkeypatch.setattr(controller, "AngularServo", make_servo)
    monkeypatch.setattr(controller, "load_servo_angle", lambda: 25.0)

    controller.initialize_hardware()

    assert servo_settings == [
        {
            "initial_angle": None,
            "min_angle": -90.0,
            "max_angle": 90.0,
            "min_pulse_width": 0.001,
            "max_pulse_width": 0.002,
            "frame_width": 0.02,
        }
    ]
    assert controller.current_servo_angle == 25.0


def test_servo_angle_is_persisted_across_controller_restarts(monkeypatch, tmp_path):
    monkeypatch.setattr(controller, "DATABASE_PATH", tmp_path / "controller.db")

    controller.initialize_database()
    assert controller.load_servo_angle() == 0.0

    controller.save_servo_angle(15.0)

    assert controller.load_servo_angle() == 15.0


def test_rotate_camera_moves_five_degrees_from_the_last_angle(monkeypatch):
    fake_servo = FakeServo()
    saved_angles: list[float] = []
    monkeypatch.setattr(controller, "servo", fake_servo)
    monkeypatch.setattr(controller, "current_servo_angle", 0.0, raising=False)
    monkeypatch.setattr(controller, "save_servo_angle", saved_angles.append)
    monkeypatch.setattr(controller.time, "sleep", lambda seconds: None)

    controller.rotate_camera(-controller.SERVO_STEP_DEGREES)
    controller.rotate_camera(-controller.SERVO_STEP_DEGREES)
    controller.rotate_camera(controller.SERVO_STEP_DEGREES)

    assert controller.current_servo_angle == -5.0
    assert fake_servo.angles == [-5.0, -10.0, -5.0]
    assert saved_angles == [-5.0, -10.0, -5.0]
    assert fake_servo.detach_count == 3


def test_rotate_camera_detaches_even_if_angle_persistence_fails(monkeypatch):
    fake_servo = FakeServo()
    monkeypatch.setattr(controller, "servo", fake_servo)
    monkeypatch.setattr(controller, "current_servo_angle", 0.0, raising=False)
    monkeypatch.setattr(
        controller,
        "save_servo_angle",
        lambda angle: (_ for _ in ()).throw(RuntimeError("database unavailable")),
    )

    with pytest.raises(RuntimeError, match="database unavailable"):
        controller.rotate_camera(controller.SERVO_STEP_DEGREES)

    assert fake_servo.detach_count == 1


@pytest.mark.parametrize(
    ("starting_angle", "delta", "expected_angle"),
    [
        (-60.0, -5.0, -60.0),
        (-58.0, -5.0, -60.0),
        (58.0, 5.0, 60.0),
        (60.0, 5.0, 60.0),
    ],
)
def test_rotate_camera_clamps_to_the_safe_range(
    monkeypatch,
    starting_angle,
    delta,
    expected_angle,
):
    fake_servo = FakeServo()
    monkeypatch.setattr(controller, "servo", fake_servo)
    monkeypatch.setattr(
        controller,
        "current_servo_angle",
        starting_angle,
        raising=False,
    )
    monkeypatch.setattr(controller, "save_servo_angle", lambda angle: None)
    monkeypatch.setattr(controller.time, "sleep", lambda seconds: None)

    controller.rotate_camera(delta)

    assert controller.current_servo_angle == expected_angle
    assert fake_servo.angles == [expected_angle]


def test_close_hardware_does_not_return_the_servo_to_center(monkeypatch):
    fake_servo = FakeServo()
    monkeypatch.setattr(controller, "servo", fake_servo)
    monkeypatch.setattr(controller, "motor_a", None)
    monkeypatch.setattr(controller, "motor_b", None)
    monkeypatch.setattr(controller, "standby_pin", None)
    monkeypatch.setattr(controller, "speaker", None)

    controller.close_hardware()

    assert fake_servo.angles == []
    assert fake_servo.closed is True


@pytest.mark.parametrize(
    ("command", "expected_delta"),
    [
        ("ROTATE_CAMERA_LEFT", -5.0),
        ("ROTATE_CAMERA_RIGHT", 5.0),
    ],
)
def test_mqtt_rotation_commands_use_exactly_five_degree_steps(
    monkeypatch,
    command,
    expected_delta,
):
    deltas: list[float] = []
    acknowledgements: list[dict[str, str]] = []
    monkeypatch.setattr(controller, "load_ack", lambda command_id: None)
    monkeypatch.setattr(controller, "rotate_camera", deltas.append)
    monkeypatch.setattr(
        controller,
        "publish_ack",
        lambda client, ack: acknowledgements.append(ack),
    )
    payload = {
        "commandId": f"test-{command.lower()}",
        "deviceId": controller.DEVICE_ID,
        "command": command,
        "durationMs": None,
        "expiresAt": "2099-01-01T00:00:00Z",
    }

    controller.handle_command(SimpleNamespace(), json.dumps(payload).encode())

    assert deltas == [expected_delta]
    assert acknowledgements[0]["status"] == "EXECUTED"
