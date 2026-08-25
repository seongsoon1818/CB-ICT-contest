from fastapi.testclient import TestClient

from app.main import create_app
from app.settings import Settings


def test_live_returns_up() -> None:
    with TestClient(create_app(Settings(backend_base_url=None))) as client:
        response = client.get("/health/live")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_ready_returns_ready_when_backend_url_exists() -> None:
    with TestClient(
        create_app(Settings(backend_base_url="http://localhost:8080"))
    ) as client:
        response = client.get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "READY",
        "inference": "mock",
        "runtime": "mock",
        "bundleVersion": None,
        "detectorVersion": "mock-animal-detector-v1",
        "classifierVersion": None,
    }


def test_ready_returns_503_when_backend_url_is_missing() -> None:
    with TestClient(create_app(Settings(backend_base_url=None))) as client:
        response = client.get("/health/ready")

    assert response.status_code == 503
    assert response.json() == {"detail": "BACKEND_BASE_URL is not configured"}
