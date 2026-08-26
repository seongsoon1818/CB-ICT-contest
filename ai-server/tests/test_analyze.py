import json
import logging
from collections.abc import Iterator
from contextlib import contextmanager
from io import BytesIO
from pathlib import Path
from typing import Any
from uuid import UUID

import pytest
from fastapi.testclient import TestClient
from jsonschema import Draft202012Validator, FormatChecker
from PIL import Image

from app.backend_client import BackendConflict, BackendUnavailable
from app.inference import DecodedFrame
from app.main import MAX_JPEG_BYTES, create_app
from app.schemas import DetectionEvent
from app.settings import FrameEvidenceSettings, Settings


class RecordingBackendClient:
    def __init__(self) -> None:
        self.event: DetectionEvent | None = None

    async def send_detection_event(
        self, event: DetectionEvent
    ) -> dict[str, Any]:
        self.event = event
        return {
            "eventId": str(event.eventId),
            "riskScore": 50,
            "riskLevel": "MEDIUM",
        }


class FailingBackendClient:
    def __init__(self, error: Exception) -> None:
        self._error = error

    async def send_detection_event(
        self, event: DetectionEvent
    ) -> dict[str, Any]:
        raise self._error


class RecordingFrameEvidenceStore:
    def __init__(self, error: Exception | None = None) -> None:
        self.calls: list[tuple[bytes, DetectionEvent]] = []
        self._error = error

    def record(self, frame_bytes: bytes, event: DetectionEvent) -> bool:
        self.calls.append((frame_bytes, event))
        if self._error is not None:
            raise self._error
        return True


def make_jpeg(width: int = 1280, height: int = 720) -> bytes:
    buffer = BytesIO()
    Image.new("RGB", (width, height), "white").save(buffer, format="JPEG")
    return buffer.getvalue()


def make_jpeg_header_with_dimensions(width: int, height: int) -> bytes:
    data = bytearray(make_jpeg(1, 1))
    start_of_frame = data.find(b"\xff\xc0")
    assert start_of_frame >= 0
    data[start_of_frame + 5 : start_of_frame + 7] = height.to_bytes(2, "big")
    data[start_of_frame + 7 : start_of_frame + 9] = width.to_bytes(2, "big")
    return bytes(data)


@contextmanager
def make_client(
    mock_result: str = "detected",
) -> Iterator[tuple[TestClient, RecordingBackendClient]]:
    backend_client = RecordingBackendClient()
    app = create_app(
        Settings(
            backend_base_url="http://backend.example",
            mock_result=mock_result,  # type: ignore[arg-type]
        ),
        backend_client=backend_client,
    )
    with TestClient(app) as client:
        yield client, backend_client


def analyze(
    client: TestClient,
    frame: bytes,
    *,
    content_type: str = "image/jpeg",
    camera_id: str = "cam-001",
    captured_at: str = "2026-08-25T02:00:00+09:00",
):
    return client.post(
        "/api/v1/analyze",
        files={"frame": ("frame.jpg", frame, content_type)},
        data={"cameraId": camera_id, "capturedAt": captured_at},
    )


def test_valid_jpeg_builds_detector_only_event_and_returns_backend_body() -> None:
    with make_client() as (client, backend_client):
        response = analyze(client, make_jpeg())

    assert response.status_code == 200
    assert response.json()["riskLevel"] == "MEDIUM"
    assert backend_client.event is not None
    event = backend_client.event
    assert UUID(str(event.eventId)).version == 4
    assert event.cameraId == "cam-001"
    assert event.image.width == 1280
    assert event.image.height == 720

    payload = event.model_dump(mode="json")
    assert payload["model"] == {
        "detectorVersion": "mock-animal-detector-v1",
        "classifierVersion": None,
    }
    assert payload["detections"][0]["trackId"] is None
    assert payload["detections"][0]["classCode"] == "MAGPIE"
    assert payload["detections"][0]["classificationConfidence"] is None
    assert "eventId" in payload
    assert "cameraId" in payload
    assert "capturedAt" in payload

    bbox = event.detections[0].bbox
    assert bbox.x >= 0
    assert bbox.y >= 0
    assert bbox.width >= 1
    assert bbox.height >= 1
    assert bbox.x + bbox.width <= event.image.width
    assert bbox.y + bbox.height <= event.image.height


def test_detected_mode_builds_valid_bbox_for_one_pixel_image() -> None:
    with make_client() as (client, backend_client):
        response = analyze(client, make_jpeg(1, 1))

    assert response.status_code == 200
    assert backend_client.event is not None
    assert backend_client.event.detections[0].bbox.model_dump() == {
        "x": 0,
        "y": 0,
        "width": 1,
        "height": 1,
    }


def test_empty_mode_keeps_empty_detections_array() -> None:
    with make_client("empty") as (client, backend_client):
        response = analyze(client, make_jpeg(20, 10))

    assert response.status_code == 200
    assert backend_client.event is not None
    assert backend_client.event.model_dump(mode="json")["detections"] == []


@pytest.mark.parametrize(
    "captured_at",
    ["2026-08-25T02:00:00+09:00", "2026-08-24T17:00:00Z"],
)
def test_generated_event_matches_repository_json_schema(captured_at: str) -> None:
    with make_client() as (client, backend_client):
        response = analyze(
            client,
            make_jpeg(640, 480),
            captured_at=captured_at,
        )
    schema_path = (
        Path(__file__).resolve().parents[2]
        / "docs/contracts/detection-event-v1.schema.json"
    )
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    assert response.status_code == 200
    assert backend_client.event is not None
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = list(
        validator.iter_errors(backend_client.event.model_dump(mode="json"))
    )
    assert errors == []


def test_empty_file_returns_400() -> None:
    with make_client() as (client, _):
        response = analyze(client, b"")

    assert response.status_code == 400


def test_non_jpeg_bytes_return_400() -> None:
    with make_client() as (client, _):
        response = analyze(client, b"not-a-jpeg")

    assert response.status_code == 400


def test_truncated_jpeg_returns_400() -> None:
    with make_client() as (client, _):
        response = analyze(client, make_jpeg()[:-100])

    assert response.status_code == 400


def test_non_jpeg_content_type_returns_400() -> None:
    with make_client() as (client, _):
        response = analyze(client, make_jpeg(), content_type="image/png")

    assert response.status_code == 400


def test_jpeg_content_type_with_parameter_is_accepted() -> None:
    with make_client() as (client, _):
        response = analyze(
            client,
            make_jpeg(),
            content_type="Image/JPEG; charset=utf-8",
        )

    assert response.status_code == 200


def test_oversized_frame_returns_413() -> None:
    with make_client() as (client, _):
        response = analyze(client, b"x" * (MAX_JPEG_BYTES + 1))

    assert response.status_code == 413


def test_image_over_pixel_budget_returns_413_before_full_decode() -> None:
    compact_jpeg_with_large_dimensions = make_jpeg_header_with_dimensions(
        width=8_000,
        height=5_001,
    )

    with make_client() as (client, backend_client):
        response = analyze(client, compact_jpeg_with_large_dimensions)

    assert len(compact_jpeg_with_large_dimensions) < 5 * 1024 * 1024
    assert response.status_code == 413
    assert backend_client.event is None


def test_invalid_camera_id_returns_422() -> None:
    with make_client() as (client, _):
        response = analyze(client, make_jpeg(), camera_id="-invalid")

    assert response.status_code == 422


def test_timezone_naive_captured_at_returns_422() -> None:
    with make_client() as (client, _):
        response = analyze(
            client,
            make_jpeg(),
            captured_at="2026-08-25T02:00:00",
        )

    assert response.status_code == 422


def test_backend_failure_returns_502_without_internal_details() -> None:
    app = create_app(
        Settings(backend_base_url="http://backend.internal"),
        backend_client=FailingBackendClient(BackendUnavailable()),
    )
    with TestClient(app) as client:
        response = analyze(client, make_jpeg())

    assert response.status_code == 502
    assert "backend.internal" not in response.text


def test_backend_conflict_returns_409() -> None:
    app = create_app(
        Settings(backend_base_url="http://backend.example"),
        backend_client=FailingBackendClient(BackendConflict()),
    )
    with TestClient(app) as client:
        response = analyze(client, make_jpeg())

    assert response.status_code == 409


def test_analyze_closes_decoded_image(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    image = Image.new("RGB", (20, 10), "white")
    decoded_frame = DecodedFrame(image=image, width=20, height=10)
    monkeypatch.setattr("app.main.decode_jpeg", lambda data: decoded_frame)
    with make_client() as (client, _):
        response = analyze(client, make_jpeg())

    assert response.status_code == 200
    with pytest.raises(ValueError, match="closed image"):
        image.getpixel((0, 0))


def test_successful_backend_response_records_exact_frame_and_event() -> None:
    frame_bytes = make_jpeg(320, 240)
    backend_client = RecordingBackendClient()
    evidence_store = RecordingFrameEvidenceStore()
    app = create_app(
        Settings(backend_base_url="http://backend.example"),
        backend_client=backend_client,
        frame_evidence_store=evidence_store,
    )

    with TestClient(app) as client:
        response = analyze(client, frame_bytes)

    assert response.status_code == 200
    assert backend_client.event is not None
    assert evidence_store.calls == [(frame_bytes, backend_client.event)]


def test_backend_failure_does_not_record_frame_evidence() -> None:
    evidence_store = RecordingFrameEvidenceStore()
    app = create_app(
        Settings(backend_base_url="http://backend.example"),
        backend_client=FailingBackendClient(BackendUnavailable()),
        frame_evidence_store=evidence_store,
    )

    with TestClient(app) as client:
        response = analyze(client, make_jpeg())

    assert response.status_code == 502
    assert evidence_store.calls == []


def test_frame_evidence_failure_is_logged_without_changing_success(
    caplog: pytest.LogCaptureFixture,
) -> None:
    backend_client = RecordingBackendClient()
    evidence_store = RecordingFrameEvidenceStore(OSError("disk unavailable"))
    app = create_app(
        Settings(backend_base_url="http://backend.example"),
        backend_client=backend_client,
        frame_evidence_store=evidence_store,
    )

    with caplog.at_level(logging.ERROR, logger="app.main"):
        with TestClient(app) as client:
            response = analyze(client, make_jpeg())

    assert response.status_code == 200
    assert len(evidence_store.calls) == 1
    assert "Frame evidence write failed" in caplog.text
    assert "disk unavailable" in caplog.text


def test_rolling_settings_construct_store_and_write_complete_pair(
    tmp_path: Path,
) -> None:
    backend_client = RecordingBackendClient()
    app = create_app(
        Settings(
            backend_base_url="http://backend.example",
            frame_evidence=FrameEvidenceSettings(
                mode="rolling",
                directory=tmp_path,
                max_files_per_camera=60,
                min_interval_seconds=0.0,
                max_bytes_per_camera=100 * 1024 * 1024,
            ),
        ),
        backend_client=backend_client,
    )

    with TestClient(app) as client:
        response = analyze(client, make_jpeg())

    assert response.status_code == 200
    jpeg_files = list((tmp_path / "cam-001").glob("*.jpg"))
    assert len(jpeg_files) == 1
    assert jpeg_files[0].with_suffix(".json").is_file()


def test_frame_evidence_initialization_failure_does_not_stop_analyze(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    unusable_directory = tmp_path / "not-a-directory"
    unusable_directory.write_text("occupied", encoding="utf-8")
    backend_client = RecordingBackendClient()

    with caplog.at_level(logging.ERROR, logger="app.main"):
        app = create_app(
            Settings(
                backend_base_url="http://backend.example",
                frame_evidence=FrameEvidenceSettings(
                    mode="rolling",
                    directory=unusable_directory,
                ),
            ),
            backend_client=backend_client,
        )
        with TestClient(app) as client:
            response = analyze(client, make_jpeg())

    assert response.status_code == 200
    assert backend_client.event is not None
    assert "Frame evidence initialization failed" in caplog.text
