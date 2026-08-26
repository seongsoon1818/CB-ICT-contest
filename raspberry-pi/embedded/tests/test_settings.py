import pytest

from animalguard_embedded.settings import Settings
from tests.helpers import valid_env


def test_settings_reads_every_externalized_value_and_redacts_password():
    settings = Settings.from_mapping(valid_env())

    assert settings.host == "127.0.0.1"
    assert settings.port == 1883
    assert settings.device_id == "pi-001"
    assert settings.processed_command_db.as_posix() == "data/processed_commands.db"
    assert settings.servo_step_degrees == 5
    assert settings.max_motor_duration_ms == 5000
    assert "fake-test-password" not in repr(settings)


@pytest.mark.parametrize(
    "missing",
    [
        "MQTT_HOST",
        "MQTT_DEVICE_ID",
        "PROCESSED_COMMAND_DB",
        "MOTOR_IN1_PIN",
        "MOTOR_IN2_PIN",
        "MOTOR_SLEEP_PIN",
        "SERVO_PIN",
        "SPEAKER_PIN",
        "MAX_MOTOR_DURATION_MS",
        "MAX_SOUND_DURATION_MS",
    ],
)
def test_settings_rejects_missing_required_values(missing):
    values = valid_env()
    values[missing] = ""

    with pytest.raises(ValueError, match=missing):
        Settings.from_mapping(values)


@pytest.mark.parametrize(
    "overrides",
    [
        {"MQTT_PORT": "0"},
        {"MQTT_PORT": "65536"},
        {"MQTT_KEEPALIVE_SECONDS": "0"},
        {"MAX_MOTOR_DURATION_MS": "-1"},
        {"MAX_SOUND_DURATION_MS": "0"},
        {"SERVO_STEP_DEGREES": "0"},
        {"SERVO_MIN_ANGLE": "10"},
        {"SERVO_MAX_ANGLE": "-10"},
        {"SERVO_STEP_DEGREES": "200"},
        {"SERVO_STEP_DEGREES": "nan"},
        {"SERVO_MAX_ANGLE": "inf"},
        {"MOTOR_IN1_PIN": "17", "MOTOR_IN2_PIN": "17"},
        {"MQTT_USERNAME": "", "MQTT_PASSWORD": "secret-without-user"},
    ],
)
def test_settings_rejects_unsafe_values(overrides):
    with pytest.raises(ValueError):
        Settings.from_mapping(valid_env(**overrides))
