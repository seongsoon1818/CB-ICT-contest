from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, model_validator


CAMERA_ID_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._-]*$"
DETECTION_ID_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._-]*$"
CLASS_CODE_PATTERN = r"^[A-Z][A-Z0-9_]*$"


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ImageInfo(ContractModel):
    width: int = Field(ge=1)
    height: int = Field(ge=1)


class ModelInfo(ContractModel):
    detectorVersion: str = Field(min_length=1, max_length=128)
    classifierVersion: str | None = Field(
        min_length=1,
        max_length=128,
        pattern=r"\S",
    )


class Bbox(ContractModel):
    x: int = Field(ge=0)
    y: int = Field(ge=0)
    width: int = Field(ge=1)
    height: int = Field(ge=1)


class Detection(ContractModel):
    detectionId: str = Field(
        min_length=1,
        max_length=64,
        pattern=DETECTION_ID_PATTERN,
    )
    trackId: int | None = Field(ge=0)
    classCode: str = Field(
        min_length=1,
        max_length=64,
        pattern=CLASS_CODE_PATTERN,
    )
    detectionConfidence: float = Field(ge=0, le=1)
    classificationConfidence: float | None = Field(ge=0, le=1)
    bbox: Bbox


class DetectionEvent(ContractModel):
    eventId: UUID
    cameraId: str = Field(
        min_length=1,
        max_length=64,
        pattern=CAMERA_ID_PATTERN,
    )
    capturedAt: datetime
    image: ImageInfo
    model: ModelInfo
    detections: list[Detection] = Field(max_length=100)

    @model_validator(mode="after")
    def validate_contract_relationships(self) -> "DetectionEvent":
        if self.capturedAt.tzinfo is None or self.capturedAt.utcoffset() is None:
            raise ValueError("capturedAt must include a timezone")

        classifier_present = self.model.classifierVersion is not None
        if any(
            (detection.classificationConfidence is not None) != classifier_present
            for detection in self.detections
        ):
            raise ValueError(
                "classificationConfidence must match classifierVersion"
            )

        detection_ids = [detection.detectionId for detection in self.detections]
        if len(detection_ids) != len(set(detection_ids)):
            raise ValueError("detectionId must be unique within an event")

        for detection in self.detections:
            bbox = detection.bbox
            if (
                bbox.x + bbox.width > self.image.width
                or bbox.y + bbox.height > self.image.height
            ):
                raise ValueError("bbox must fit within image boundaries")
        return self
