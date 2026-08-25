from datetime import datetime
from typing import Annotated, Any, Protocol
from uuid import uuid4

from fastapi import FastAPI, File, Form, HTTPException, UploadFile, status

from app.backend_client import BackendClient, BackendConflict, BackendUnavailable
from app.inference import (
    DETECTOR_VERSION,
    FrameTooLargeError,
    InvalidJpegError,
    MockInference,
    decode_jpeg,
)
from app.schemas import CAMERA_ID_PATTERN, DetectionEvent, ImageInfo, ModelInfo
from app.settings import Settings


MAX_JPEG_BYTES = 5 * 1024 * 1024


class BackendClientLike(Protocol):
    async def send_detection_event(
        self, event: DetectionEvent
    ) -> dict[str, Any]: ...


def create_app(
    settings: Settings | None = None,
    backend_client: BackendClientLike | None = None,
) -> FastAPI:
    app_settings = settings or Settings.from_env()
    inference = MockInference(app_settings.mock_result)
    configured_backend_client = backend_client
    if configured_backend_client is None and app_settings.backend_base_url is not None:
        configured_backend_client = BackendClient(app_settings.backend_base_url)
    application = FastAPI(title="AnimalGuard AI Server")

    @application.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/health/ready")
    async def ready() -> dict[str, str]:
        if app_settings.backend_base_url is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="BACKEND_BASE_URL is not configured",
            )
        return {"status": "READY", "inference": "mock"}

    @application.post("/api/v1/analyze")
    async def analyze(
        frame: Annotated[UploadFile, File()],
        cameraId: Annotated[
            str,
            Form(min_length=1, max_length=64, pattern=CAMERA_ID_PATTERN),
        ],
        capturedAt: Annotated[datetime, Form()],
    ) -> dict[str, Any]:
        frame_media_type = (frame.content_type or "").partition(";")[0]
        if frame_media_type.strip().lower() != "image/jpeg":
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="frame must use image/jpeg Content-Type",
            )

        frame_bytes = await frame.read(MAX_JPEG_BYTES + 1)
        if not frame_bytes:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="frame must not be empty",
            )
        if len(frame_bytes) > MAX_JPEG_BYTES:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail="frame exceeds the 5 MiB limit",
            )
        if capturedAt.tzinfo is None or capturedAt.utcoffset() is None:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
                detail="capturedAt must include a timezone",
            )

        try:
            width, height = decode_jpeg(frame_bytes)
        except FrameTooLargeError as error:
            raise HTTPException(
                status_code=status.HTTP_413_CONTENT_TOO_LARGE,
                detail=str(error),
            ) from error
        except InvalidJpegError as error:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=str(error),
            ) from error

        event = DetectionEvent(
            eventId=uuid4(),
            cameraId=cameraId,
            capturedAt=capturedAt,
            image=ImageInfo(width=width, height=height),
            model=ModelInfo(
                detectorVersion=DETECTOR_VERSION,
                classifierVersion=None,
            ),
            detections=inference.analyze(width, height),
        )
        if configured_backend_client is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Backend client is not configured",
            )
        try:
            return await configured_backend_client.send_detection_event(event)
        except BackendConflict as error:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="Backend reported a duplicate event",
            ) from error
        except BackendUnavailable as error:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Backend request failed",
            ) from error

    return application


app = create_app()
