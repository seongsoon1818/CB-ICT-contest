import logging
from typing import Any

import httpx

from app.schemas import DetectionEvent


BACKEND_TIMEOUT_SECONDS = 5.0
DETECTION_EVENTS_PATH = "/api/v1/detection/events"

logger = logging.getLogger(__name__)


class BackendConflict(Exception):
    pass


class BackendUnavailable(Exception):
    pass


class BackendClient:
    def __init__(
        self,
        base_url: str,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._transport = transport

    async def send_detection_event(
        self, event: DetectionEvent
    ) -> dict[str, Any]:
        try:
            async with httpx.AsyncClient(
                base_url=self._base_url,
                timeout=BACKEND_TIMEOUT_SECONDS,
                transport=self._transport,
            ) as client:
                response = await client.post(
                    DETECTION_EVENTS_PATH,
                    json=event.model_dump(mode="json"),
                )
        except httpx.RequestError as error:
            raise BackendUnavailable from error

        if response.status_code == 409:
            raise BackendConflict
        if response.status_code != 201:
            if response.status_code == 400:
                logger.error(
                    "Backend rejected Detection Event: status=%s body=%s",
                    response.status_code,
                    response.text,
                )
            raise BackendUnavailable

        try:
            body = response.json()
        except ValueError as error:
            raise BackendUnavailable from error
        if not isinstance(body, dict):
            raise BackendUnavailable
        return body
