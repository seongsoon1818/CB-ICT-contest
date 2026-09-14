import pytest

from animalguard_mqtt.settings import Settings


def valid_env(**overrides):
    values = {
        "MQTT_HOST": "127.0.0.1",
        "MQTT_PORT": "1883",
        "MQTT_DEVICE_ID": "pi-001",
        "MQTT_KEEPALIVE_SECONDS": "30",
        "PROCESSED_COMMAND_DB": "./data/processed_commands.db",
        "STATUS_INTERVAL_SECONDS": "30",
    }
    values.update(overrides)
    return values


def test_settings_from_env():
    settings = Settings.from_mapping(valid_env())

    assert settings.port == 1883
    assert settings.device_id == "pi-001"
    assert settings.keepalive_seconds == 30


@pytest.mark.parametrize(
    "overrides",
    [
        {"MQTT_PORT": "0"},
        {"MQTT_PORT": "65536"},
        {"MQTT_DEVICE_ID": "  "},
        {"MQTT_KEEPALIVE_SECONDS": "0"},
        {"STATUS_INTERVAL_SECONDS": "0"},
    ],
)
def test_settings_reject_invalid_values(overrides):
    with pytest.raises(ValueError):
        Settings.from_mapping(valid_env(**overrides))
