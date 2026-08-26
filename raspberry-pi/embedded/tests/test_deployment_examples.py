from pathlib import Path

ENV_PATH = Path(__file__).parents[1] / ".env.example"
ROOT_ENV_PATH = Path(__file__).parents[3] / ".env.example"


def _read_assignments() -> dict[str, str]:
    return _read_assignments_from(ENV_PATH)


def _read_assignments_from(path: Path) -> dict[str, str]:
    return {
        key: value
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
        for key, value in [line.split("=", 1)]
    }


def test_example_connects_pi_001_without_guessing_broker_credentials() -> None:
    assignments = _read_assignments()

    assert assignments["MQTT_HOST"] == ""
    assert assignments["MQTT_USERNAME"] == ""
    assert assignments["MQTT_PASSWORD"] == ""
    assert assignments["MQTT_DEVICE_ID"] == "pi-001"
    assert assignments["MQTT_PORT"] == "1883"
    assert assignments["MQTT_KEEPALIVE_SECONDS"] == "60"


def test_example_uses_the_systemd_state_directory_without_guessing_hardware() -> None:
    assignments = _read_assignments()

    assert assignments["PROCESSED_COMMAND_DB"] == (
        "/var/lib/animalguard/processed_commands.db"
    )
    for name in (
        "MOTOR_IN1_PIN",
        "MOTOR_IN2_PIN",
        "MOTOR_SLEEP_PIN",
        "SERVO_PIN",
        "SPEAKER_PIN",
        "MAX_MOTOR_DURATION_MS",
        "MAX_SOUND_DURATION_MS",
    ):
        assert assignments[name] == ""


def test_backend_example_keeps_every_actuation_gate_disabled() -> None:
    assignments = _read_assignments_from(ROOT_ENV_PATH)

    assert assignments["MQTT_ENABLED"] == "false"
    assert assignments["MQTT_CLIENT_ID"] == "animalguard-backend"
    assert assignments["ACTUATION_ENABLED"] == "false"
    assert assignments["RISK_POLICY_CONFIRMED"] == "false"
    assert assignments["RESPONSE_POLICY_ENABLED"] == "false"
    assert assignments["OPERATOR_API_ENABLED"] == "false"
