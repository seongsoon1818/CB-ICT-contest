from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ImageInfo(ContractModel):
    width: int = Field(ge=1)
    height: int = Field(ge=1)


class ModelInfo(ContractModel):
    detectorVersion: str
    classifierVersion: str | None


class Bbox(ContractModel):
    x: int = Field(ge=0)
    y: int = Field(ge=0)
    width: int = Field(ge=1)
    height: int = Field(ge=1)


class Detection(ContractModel):
    detectionId: str
    trackId: int | None
    classCode: str
    detectionConfidence: float = Field(ge=0, le=1)
    classificationConfidence: float | None = Field(ge=0, le=1)
    bbox: Bbox


class DetectionEvent(ContractModel):
    eventId: UUID
    cameraId: str
    capturedAt: datetime
    image: ImageInfo
    model: ModelInfo
    detections: list[Detection]
