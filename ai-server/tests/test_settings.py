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


def test_from_env_rejects_unknown_mock_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("MOCK_RESULT", "unknown")

    with pytest.raises(ValueError, match="MOCK_RESULT"):
        Settings.from_env()
