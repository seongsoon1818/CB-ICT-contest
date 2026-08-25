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
