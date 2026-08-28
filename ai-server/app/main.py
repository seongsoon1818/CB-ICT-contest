import asyncio
import logging
from collections.abc import AsyncIterator, Callable
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Annotated, Any, Protocol
from uuid import uuid4

from fastapi import FastAPI, File, Form, HTTPException, UploadFile, status

from app.backend_client import BackendClient, BackendConflict, BackendUnavailable
from app.engine_factory import create_inference_engine
from app.frame_evidence import RollingFrameEvidenceStore
from app.inference import (
    FrameTooLargeError,
    InferenceEngine,
    InferenceRuntimeError,
    InvalidJpegError,
    decode_jpeg,
)
from app.schemas import CAMERA_ID_PATTERN, DetectionEvent, ImageInfo, ModelInfo
from app.settings import Settings

MAX_JPEG_BYTES = 5 * 1024 * 1024
logger = logging.getLogger(__name__)
InferenceEngineFactory = Callable[[Settings], InferenceEngine]


class BackendClientLike(Protocol):
    async def send_detection_event(
        self, event: DetectionEvent
    ) -> dict[str, Any]: ...


class FrameEvidenceStoreLike(Protocol):
    def record(
        self,
        frame_bytes: bytes,
        event: DetectionEvent,
        backend_analysis: dict[str, Any],
    ) -> bool: ...


def create_app(
    settings: Settings | None = None,
    backend_client: BackendClientLike | None = None,
    inference_engine_factory: InferenceEngineFactory | None = None,
    frame_evidence_store: FrameEvidenceStoreLike | None = None,
) -> FastAPI:
    app_settings = settings or Settings.from_env()
    configured_engine_factory = (
        inference_engine_factory or create_inference_engine
    )
    configured_backend_client = backend_client
    if configured_backend_client is None and app_settings.backend_base_url is not None:
        configured_backend_client = BackendClient(app_settings.backend_base_url)
    configured_evidence_store = frame_evidence_store
    if (
        configured_evidence_store is None
        and app_settings.frame_evidence.mode == "rolling"
    ):
        try:
            configured_evidence_store = RollingFrameEvidenceStore(
                app_settings.frame_evidence
            )
        except OSError:
            logger.exception(
                "Frame evidence initialization failed; storage is disabled"
            )
            configured_evidence_store = None

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        engine: InferenceEngine | None = None
        application.state.inference_engine = None
        application.state.inference_load_error = None
        try:
            engine = configured_engine_factory(app_settings)
            application.state.inference_engine = engine
        except Exception as error:
            application.state.inference_load_error = error
            logger.exception(
                "Inference engine failed to load: mode=%s",
                app_settings.inference_mode,
            )
        try:
            yield
        finally:
            if engine is not None:
                try:
                    engine.close()
                except Exception:
                    logger.exception("Inference engine failed to close")

    application = FastAPI(
        title="AnimalGuard AI Server",
        lifespan=lifespan,
    )

    def require_inference_engine() -> InferenceEngine:
        engine = application.state.inference_engine
        if engine is None or not engine.ready:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Inference engine is not ready",
            )
        return engine

    @application.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/health/ready")
    async def ready() -> dict[str, str | None]:
        if app_settings.backend_base_url is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="BACKEND_BASE_URL is not configured",
            )
        metadata = require_inference_engine().metadata
        return {
            "status": "READY",
            "inference": metadata.mode,
            "runtime": metadata.runtime,
            "bundleVersion": metadata.bundle_version,
            "detectorVersion": metadata.detector_version,
            "classifierVersion": metadata.classifier_version,
        }

    @application.post("/api/v1/analyze")
    async def analyze(
        frame: Annotated[UploadFile, File()],
        cameraId: Annotated[
            str,
            Form(min_length=1, max_length=64, pattern=CAMERA_ID_PATTERN),
        ],
        capturedAt: Annotated[datetime, Form()],
    ) -> dict[str, Any]:
        inference = require_inference_engine()
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
            decoded_frame = decode_jpeg(frame_bytes)
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

        try:
            metadata = inference.metadata
            try:
                detections = inference.analyze(decoded_frame)
            except InferenceRuntimeError as error:
                application.state.inference_engine = None
                application.state.inference_load_error = error
                logger.exception(
                    "Inference execution failed: mode=%s",
                    app_settings.inference_mode,
                )
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="Inference engine failed",
                ) from error
            event = DetectionEvent(
                eventId=uuid4(),
                cameraId=cameraId,
                capturedAt=capturedAt,
                image=ImageInfo(
                    width=decoded_frame.width,
                    height=decoded_frame.height,
                ),
                model=ModelInfo(
                    detectorVersion=metadata.detector_version,
                    classifierVersion=metadata.classifier_version,
                ),
                detections=detections,
            )
            if configured_backend_client is None:
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="Backend client is not configured",
                )
            try:
                backend_response = (
                    await configured_backend_client.send_detection_event(event)
                )
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
            if configured_evidence_store is not None:
                try:
                    await asyncio.to_thread(
                        configured_evidence_store.record,
                        frame_bytes,
                        event,
                        backend_response,
                    )
                except Exception:
                    logger.exception(
                        "Frame evidence write failed: camera_id=%s event_id=%s",
                        event.cameraId,
                        event.eventId,
                    )
            return backend_response
        finally:
            decoded_frame.image.close()

    return application


app = create_app()
