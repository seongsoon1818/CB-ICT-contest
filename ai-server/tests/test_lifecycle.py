from io import BytesIO
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient
from PIL import Image

from app.inference import DecodedFrame, InferenceMetadata
from app.main import create_app
from app.schemas import DetectionEvent
from app.settings import Settings


class FakeInferenceEngine:
    def __init__(self, metadata: InferenceMetadata) -> None:
        self._metadata = metadata
        self.analyze_count = 0
        self.close_count = 0

    @property
    def metadata(self) -> InferenceMetadata:
        return self._metadata

    @property
    def ready(self) -> bool:
        return True

    def analyze(self, frame: DecodedFrame):
        self.analyze_count += 1
        return []

    def close(self) -> None:
        self.close_count += 1


class RecordingBackendClient:
    def __init__(self) -> None:
        self.events: list[DetectionEvent] = []

    async def send_detection_event(
        self,
        event: DetectionEvent,
    ) -> dict[str, Any]:
        self.events.append(event)
        return {
            "eventId": str(event.eventId),
            "riskScore": 0,
            "riskLevel": "LOW",
        }


def make_jpeg() -> bytes:
    buffer = BytesIO()
    Image.new("RGB", (20, 10), "white").save(buffer, format="JPEG")
    return buffer.getvalue()


def analyze(client: TestClient):
    return client.post(
        "/api/v1/analyze",
        files={"frame": ("frame.jpg", make_jpeg(), "image/jpeg")},
        data={
            "cameraId": "cam-001",
            "capturedAt": "2026-08-25T02:00:00+09:00",
        },
    )


def model_metadata(bundle_version: str = "v1") -> InferenceMetadata:
    return InferenceMetadata(
        mode="model",
        runtime="future-runtime",
        bundle_version=bundle_version,
        detector_version=f"detector-{bundle_version}",
        classifier_version=f"classifier-{bundle_version}",
    )


def test_lifespan_creates_engine_once_for_multiple_requests_and_closes_it() -> None:
    engine = FakeInferenceEngine(model_metadata())
    factory_calls = 0

    def factory(settings: Settings) -> FakeInferenceEngine:
        nonlocal factory_calls
        factory_calls += 1
        return engine

    backend = RecordingBackendClient()
    application = create_app(
        Settings(backend_base_url="http://backend.example"),
        backend_client=backend,
        inference_engine_factory=factory,
    )

    with TestClient(application) as client:
        first = analyze(client)
        second = analyze(client)
        assert factory_calls == 1
        assert engine.close_count == 0

    assert first.status_code == 200
    assert second.status_code == 200
    assert engine.analyze_count == 2
    assert engine.close_count == 1


def test_model_metadata_is_used_by_ready_and_detection_event() -> None:
    engine = FakeInferenceEngine(model_metadata("2026-08-25.1"))
    backend = RecordingBackendClient()
    application = create_app(
        Settings(
            backend_base_url="http://backend.example",
            inference_mode="model",
            model_bundle_dir=Path("/unused/by/fake"),
        ),
        backend_client=backend,
        inference_engine_factory=lambda settings: engine,
    )

    with TestClient(application) as client:
        ready = client.get("/health/ready")
        response = analyze(client)

    assert ready.json() == {
        "status": "READY",
        "inference": "model",
        "runtime": "future-runtime",
        "bundleVersion": "2026-08-25.1",
        "detectorVersion": "detector-2026-08-25.1",
        "classifierVersion": "classifier-2026-08-25.1",
    }
    assert response.status_code == 200
    assert backend.events[0].model.detectorVersion == "detector-2026-08-25.1"
    assert backend.events[0].model.classifierVersion == "classifier-2026-08-25.1"


def test_model_load_failure_keeps_live_up_and_rejects_ready_and_analyze() -> None:
    def failing_factory(settings: Settings):
        raise RuntimeError("secret failure at /opt/animalguard/models/current")

    application = create_app(
        Settings(
            backend_base_url="http://backend.example",
            inference_mode="model",
            model_bundle_dir=Path("/opt/animalguard/models/current"),
        ),
        backend_client=RecordingBackendClient(),
        inference_engine_factory=failing_factory,
    )

    with TestClient(application) as client:
        live = client.get("/health/live")
        ready = client.get("/health/ready")
        response = analyze(client)

    assert live.status_code == 200
    assert live.json() == {"status": "UP"}
    assert ready.status_code == 503
    assert response.status_code == 503
    assert "/opt/animalguard" not in ready.text
    assert "secret failure" not in ready.text
    assert "/opt/animalguard" not in response.text
    assert "secret failure" not in response.text


def test_invalid_model_bundle_does_not_fall_back_to_mock(tmp_path: Path) -> None:
    invalid_bundle = tmp_path / "invalid-bundle"
    invalid_bundle.mkdir()
    application = create_app(
        Settings(
            backend_base_url="http://backend.example",
            inference_mode="model",
            model_bundle_dir=invalid_bundle,
        ),
        backend_client=RecordingBackendClient(),
    )

    with TestClient(application) as client:
        live = client.get("/health/live")
        ready = client.get("/health/ready")
        response = analyze(client)

    assert live.status_code == 200
    assert ready.status_code == 503
    assert response.status_code == 503
    assert "mock" not in ready.text.lower()


def test_default_model_factory_validates_bundle_only_once(
    monkeypatch,
) -> None:
    load_count = 0

    class RecordingLoader:
        def load(self, directory: Path):
            nonlocal load_count
            load_count += 1
            return object()

    monkeypatch.setattr("app.engine_factory.ModelBundleLoader", RecordingLoader)
    application = create_app(
        Settings(
            backend_base_url="http://backend.example",
            inference_mode="model",
            model_bundle_dir=Path("/opt/animalguard/models/current"),
        ),
        backend_client=RecordingBackendClient(),
    )

    with TestClient(application) as client:
        first = analyze(client)
        second = analyze(client)

    assert load_count == 1
    assert first.status_code == 503
    assert second.status_code == 503
