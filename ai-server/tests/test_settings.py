from pathlib import Path

import pytest

from app.settings import Settings


def test_from_env_treats_blank_backend_url_as_missing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("BACKEND_BASE_URL", "")
    monkeypatch.delenv("MOCK_RESULT", raising=False)

    settings = Settings.from_env()

    assert settings.backend_base_url is None
    assert settings.mock_result == "detected"
    assert settings.inference_mode == "mock"
    assert settings.model_bundle_dir is None
    assert settings.frame_evidence.mode == "off"
    assert settings.frame_evidence.directory is None
    assert settings.frame_evidence.max_files_per_camera == 60
    assert settings.frame_evidence.min_interval_seconds == 1.0
    assert settings.frame_evidence.max_bytes_per_camera == 100 * 1024 * 1024


def test_from_env_rejects_unknown_mock_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("MOCK_RESULT", "unknown")

    with pytest.raises(ValueError, match="MOCK_RESULT"):
        Settings.from_env()


def test_from_env_reads_model_mode_and_bundle_directory(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("INFERENCE_MODE", "model")
    monkeypatch.setenv("MODEL_BUNDLE_DIR", "/opt/animalguard/models/current")

    settings = Settings.from_env()

    assert settings.inference_mode == "model"
    assert settings.model_bundle_dir == Path("/opt/animalguard/models/current")


def test_from_env_rejects_unknown_inference_mode(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("INFERENCE_MODE", "auto")

    with pytest.raises(ValueError, match="INFERENCE_MODE"):
        Settings.from_env()


def test_from_env_reads_rolling_frame_evidence_settings(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.setenv("FRAME_EVIDENCE_MODE", "rolling")
    monkeypatch.setenv("FRAME_EVIDENCE_DIR", str(tmp_path))
    monkeypatch.setenv("FRAME_EVIDENCE_MAX_FILES_PER_CAMERA", "12")
    monkeypatch.setenv("FRAME_EVIDENCE_MIN_INTERVAL_SECONDS", "0.5")
    monkeypatch.setenv("FRAME_EVIDENCE_MAX_BYTES_PER_CAMERA", "4096")

    settings = Settings.from_env()

    assert settings.frame_evidence.mode == "rolling"
    assert settings.frame_evidence.directory == tmp_path
    assert settings.frame_evidence.max_files_per_camera == 12
    assert settings.frame_evidence.min_interval_seconds == 0.5
    assert settings.frame_evidence.max_bytes_per_camera == 4096


def test_from_env_requires_directory_for_rolling_frame_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("FRAME_EVIDENCE_MODE", "rolling")
    monkeypatch.delenv("FRAME_EVIDENCE_DIR", raising=False)

    with pytest.raises(ValueError, match="FRAME_EVIDENCE_DIR"):
        Settings.from_env()


@pytest.mark.parametrize(
    "directory",
    ["relative/frame-evidence", "<set-locally>", "/"],
)
def test_from_env_rejects_unsafe_rolling_frame_evidence_directory(
    monkeypatch: pytest.MonkeyPatch,
    directory: str,
) -> None:
    monkeypatch.setenv("FRAME_EVIDENCE_MODE", "rolling")
    monkeypatch.setenv("FRAME_EVIDENCE_DIR", directory)

    with pytest.raises(ValueError, match="FRAME_EVIDENCE_DIR"):
        Settings.from_env()


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("FRAME_EVIDENCE_MODE", "forever"),
        ("FRAME_EVIDENCE_MAX_FILES_PER_CAMERA", "0"),
        ("FRAME_EVIDENCE_MAX_FILES_PER_CAMERA", "one"),
        ("FRAME_EVIDENCE_MIN_INTERVAL_SECONDS", "-0.1"),
        ("FRAME_EVIDENCE_MIN_INTERVAL_SECONDS", "nan"),
        ("FRAME_EVIDENCE_MAX_BYTES_PER_CAMERA", "0"),
        ("FRAME_EVIDENCE_MAX_BYTES_PER_CAMERA", "many"),
    ],
)
def test_from_env_rejects_invalid_frame_evidence_settings(
    monkeypatch: pytest.MonkeyPatch,
    name: str,
    value: str,
) -> None:
    monkeypatch.setenv(name, value)

    with pytest.raises(ValueError, match=name):
        Settings.from_env()
