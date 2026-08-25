from datetime import datetime
from uuid import uuid4

import pytest
from pydantic import ValidationError

from app.schemas import Bbox, Detection, DetectionEvent, ImageInfo, ModelInfo


def valid_detection(**overrides) -> Detection:
    values = {
        "detectionId": "det-001",
        "trackId": None,
        "classCode": "MAGPIE",
        "detectionConfidence": 0.95,
        "classificationConfidence": None,
        "bbox": Bbox(x=10, y=10, width=20, height=20),
    }
    values.update(overrides)
    return Detection(**values)


def valid_event(**overrides) -> DetectionEvent:
    values = {
        "eventId": uuid4(),
        "cameraId": "cam-001",
        "capturedAt": datetime.fromisoformat("2026-08-25T02:00:00+09:00"),
        "image": ImageInfo(width=100, height=100),
        "model": ModelInfo(
            detectorVersion="mock-animal-detector-v1",
            classifierVersion=None,
        ),
        "detections": [valid_detection()],
    }
    values.update(overrides)
    return DetectionEvent(**values)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("detectionId", " invalid"),
        ("trackId", -1),
        ("classCode", "wild boar"),
    ],
)
def test_detection_rejects_values_outside_contract(field: str, value) -> None:
    with pytest.raises(ValidationError):
        valid_detection(**{field: value})


def test_event_rejects_invalid_camera_id() -> None:
    with pytest.raises(ValidationError):
        valid_event(cameraId="-invalid")


def test_event_rejects_classifier_confidence_mismatch() -> None:
    with pytest.raises(ValidationError):
        valid_event(
            model=ModelInfo(
                detectorVersion="mock-animal-detector-v1",
                classifierVersion="classifier-v1",
            )
        )


def test_event_rejects_duplicate_detection_ids() -> None:
    with pytest.raises(ValidationError):
        valid_event(detections=[valid_detection(), valid_detection()])


def test_event_rejects_bbox_outside_image() -> None:
    with pytest.raises(ValidationError):
        valid_event(
            detections=[
                valid_detection(bbox=Bbox(x=90, y=90, width=20, height=20))
            ]
        )
