import asyncio
import json
from datetime import datetime
from uuid import uuid4

import httpx
import pytest

from app.backend_client import BackendClient, BackendConflict, BackendUnavailable
from app.schemas import Bbox, Detection, DetectionEvent, ImageInfo, ModelInfo


def make_event() -> DetectionEvent:
    return DetectionEvent(
        eventId=uuid4(),
        cameraId="cam-001",
        capturedAt=datetime.fromisoformat("2026-08-25T02:00:00+09:00"),
        image=ImageInfo(width=640, height=480),
        model=ModelInfo(
            detectorVersion="mock-animal-detector-v1",
            classifierVersion=None,
        ),
        detections=[
            Detection(
                detectionId="det-001",
                trackId=None,
                classCode="MAGPIE",
                detectionConfidence=0.95,
                classificationConfidence=None,
                bbox=Bbox(x=160, y=120, width=320, height=240),
            )
        ],
    )


def test_backend_201_returns_body_and_sends_contract_json() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == httpx.URL(
            "http://backend.example/api/v1/detection/events"
        )
        payload = json.loads(request.content)
        assert payload["model"]["classifierVersion"] is None
        assert payload["detections"][0]["trackId"] is None
        assert payload["detections"][0]["classificationConfidence"] is None
        assert "frame" not in payload
        return httpx.Response(
            201,
            json={
                "eventId": payload["eventId"],
                "riskScore": 50,
                "riskLevel": "MEDIUM",
            },
        )

    client = BackendClient(
        "http://backend.example/",
        transport=httpx.MockTransport(handler),
    )

    result = asyncio.run(client.send_detection_event(make_event()))

    assert result["riskLevel"] == "MEDIUM"


def test_backend_400_becomes_unavailable(caplog: pytest.LogCaptureFixture) -> None:
    transport = httpx.MockTransport(
        lambda request: httpx.Response(
            400,
            json={"code": "VALIDATION_ERROR", "message": "invalid event"},
        )
    )
    client = BackendClient("http://backend.example", transport=transport)

    with pytest.raises(BackendUnavailable):
        asyncio.run(client.send_detection_event(make_event()))

    assert "VALIDATION_ERROR" in caplog.text


def test_backend_409_becomes_conflict() -> None:
    transport = httpx.MockTransport(
        lambda request: httpx.Response(409, json={"code": "DUPLICATE_EVENT"})
    )
    client = BackendClient("http://backend.example", transport=transport)

    with pytest.raises(BackendConflict):
        asyncio.run(client.send_detection_event(make_event()))


def test_backend_5xx_becomes_unavailable() -> None:
    transport = httpx.MockTransport(
        lambda request: httpx.Response(503, json={"message": "unavailable"})
    )
    client = BackendClient("http://backend.example", transport=transport)

    with pytest.raises(BackendUnavailable):
        asyncio.run(client.send_detection_event(make_event()))


def test_backend_connection_failure_becomes_unavailable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection failed", request=request)

    client = BackendClient(
        "http://backend.example",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(BackendUnavailable):
        asyncio.run(client.send_detection_event(make_event()))
