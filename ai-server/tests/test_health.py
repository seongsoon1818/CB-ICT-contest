from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings


def test_live_returns_up() -> None:
    client = TestClient(create_app(Settings(backend_base_url=None)))

    response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_ready_returns_ready_when_backend_url_exists() -> None:
    client = TestClient(
        create_app(Settings(backend_base_url="http://localhost:8080"))
    )

    response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {"status": "READY", "inference": "mock"}


def test_ready_returns_503_when_backend_url_is_missing() -> None:
    client = TestClient(create_app(Settings(backend_base_url=None)))

    response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json() == {"detail": "BACKEND_BASE_URL is not configured"}
