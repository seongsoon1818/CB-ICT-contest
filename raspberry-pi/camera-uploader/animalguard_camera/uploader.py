import logging
from datetime import datetime
from enum import Enum
from typing import Any

import httpx


LOGGER = logging.getLogger(__name__)


class UploadResult(Enum):
    SUCCESS = "success"
    CLIENT_ERROR = "client_error"
    CONFIGURATION_ERROR = "configuration_error"
    TRANSIENT_ERROR = "transient_error"


class FrameUploader:
    def __init__(
        self,
        ai_server_base_url: str,
        camera_id: str,
        timeout_seconds: float,
        client: httpx.Client | None = None,
    ) -> None:
        self._endpoint = f"{ai_server_base_url.rstrip('/')}/api/v1/analyze"
        self._camera_id = camera_id
        self._client = client or httpx.Client(timeout=timeout_seconds)
        self._authentication_error_logged = False

    def upload(self, frame: bytes, captured_at: datetime) -> UploadResult:
        if captured_at.tzinfo is None or captured_at.utcoffset() is None:
            raise ValueError("captured_at must include a timezone")

        try:
            response = self._client.post(
                self._endpoint,
                files={"frame": ("frame.jpg", frame, "image/jpeg")},
                data={
                    "cameraId": self._camera_id,
                    "capturedAt": captured_at.isoformat(),
                },
            )
        except httpx.TimeoutException:
            LOGGER.warning("Frame upload timed out; discarding the current frame")
            return UploadResult.TRANSIENT_ERROR
        except httpx.RequestError as error:
            LOGGER.warning(
                "Frame upload failed; discarding the current frame (%s)",
                type(error).__name__,
            )
            return UploadResult.TRANSIENT_ERROR

        summary = _summarize_response(response)
        if 200 <= response.status_code < 300:
            LOGGER.debug(
                "Frame upload succeeded: status=%s %s",
                response.status_code,
                summary,
            )
            return UploadResult.SUCCESS
        if response.status_code in {401, 403}:
            if not self._authentication_error_logged:
                LOGGER.error(
                    "AI Server rejected uploader configuration: status=%s %s",
                    response.status_code,
                    summary,
                )
                self._authentication_error_logged = True
            return UploadResult.CONFIGURATION_ERROR
        if response.status_code in {400, 413, 422}:
            LOGGER.error(
                "AI Server rejected the current frame: status=%s %s",
                response.status_code,
                summary,
            )
            return UploadResult.CLIENT_ERROR
        if response.status_code == 409:
            LOGGER.warning(
                "AI Server reported a duplicate event; discarding the current frame: %s",
                summary,
            )
            return UploadResult.CLIENT_ERROR
        if response.status_code >= 500:
            LOGGER.warning(
                "AI Server request failed; discarding the current frame: status=%s %s",
                response.status_code,
                summary,
            )
            return UploadResult.TRANSIENT_ERROR

        LOGGER.warning(
            "Unexpected AI Server response; discarding the current frame: status=%s %s",
            response.status_code,
            summary,
        )
        return UploadResult.CLIENT_ERROR

    def close(self) -> None:
        self._client.close()


def _summarize_response(response: httpx.Response) -> str:
    try:
        body: Any = response.json()
    except ValueError:
        return f"body_bytes={len(response.content)}"
    if isinstance(body, dict):
        keys = ",".join(sorted(str(key) for key in body.keys()))
        return f"body_keys=[{keys}]"
    return f"body_type={type(body).__name__}"
