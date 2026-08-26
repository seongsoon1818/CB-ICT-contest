import pytest

from animalguard_camera.settings import Settings


def minimum_env(**overrides: str) -> dict[str, str]:
    values = {
        "AI_SERVER_BASE_URL": "http://127.0.0.1:8000/",
        "CAMERA_ID": "cam-001",
    }
    values.update(overrides)
    return values


def test_settings_load_defaults_and_normalize_url() -> None:
    settings = Settings.from_env(minimum_env())

    assert settings.ai_server_base_url == "http://127.0.0.1:8000"
    assert settings.camera_id == "cam-001"
    assert settings.capture_fps == 30.0
    assert settings.http_timeout_seconds == 10.0
    assert settings.upload_transient_backoff_seconds == 1.0
    assert settings.stats_interval_seconds == 10.0
    assert settings.camera_source == "picamera2"
    assert settings.frame_width == 1280
    assert settings.frame_height == 720


@pytest.mark.parametrize("missing_name", ["AI_SERVER_BASE_URL", "CAMERA_ID"])
def test_settings_reject_missing_required_value(missing_name: str) -> None:
    values = minimum_env()
    del values[missing_name]

    with pytest.raises(ValueError, match=f"{missing_name} is required"):
        Settings.from_env(values)


@pytest.mark.parametrize(
    "camera_id",
    ["cam-001", "A", "camera.lab_2", "9-front"],
)
def test_settings_accept_valid_camera_id(camera_id: str) -> None:
    assert Settings.from_env(minimum_env(CAMERA_ID=camera_id)).camera_id == camera_id


@pytest.mark.parametrize("camera_id", ["-cam", "cam space", "한글", "cam/1"])
def test_settings_reject_invalid_camera_id(camera_id: str) -> None:
    with pytest.raises(ValueError, match="CAMERA_ID must match"):
        Settings.from_env(minimum_env(CAMERA_ID=camera_id))


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("CAPTURE_FPS", "0"),
        ("CAPTURE_FPS", "31"),
        ("HTTP_TIMEOUT_SECONDS", "-1"),
        ("UPLOAD_TRANSIENT_BACKOFF_SECONDS", "-0.1"),
        ("STATS_INTERVAL_SECONDS", "0"),
    ],
)
def test_settings_reject_invalid_numeric_setting(name: str, value: str) -> None:
    with pytest.raises(ValueError, match=name):
        Settings.from_env(minimum_env(**{name: value}))


def test_settings_allows_zero_transient_backoff() -> None:
    settings = Settings.from_env(
        minimum_env(UPLOAD_TRANSIENT_BACKOFF_SECONDS="0")
    )

    assert settings.upload_transient_backoff_seconds == 0.0


def test_settings_rejects_deprecated_frame_interval() -> None:
    with pytest.raises(
        ValueError,
        match="FRAME_INTERVAL_SECONDS is deprecated; use CAPTURE_FPS",
    ):
        Settings.from_env(minimum_env(FRAME_INTERVAL_SECONDS="1"))


def test_file_source_requires_test_frame_path() -> None:
    with pytest.raises(ValueError, match="TEST_FRAME_PATH is required"):
        Settings.from_env(minimum_env(CAMERA_SOURCE="file"))
