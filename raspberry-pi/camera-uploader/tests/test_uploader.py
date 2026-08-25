from datetime import datetime, timezone

import httpx
import pytest

from animalguard_camera.uploader import FrameUploader, UploadResult


CAPTURED_AT = datetime(2026, 8, 25, 3, 10, 20, 123456, tzinfo=timezone.utc)


def make_uploader(handler: httpx.MockTransport) -> tuple[FrameUploader, httpx.Client]:
    client = httpx.Client(transport=handler)
    return (
        FrameUploader(
            "http://ai.example/",
            "cam-001",
            timeout_seconds=10,
            client=client,
        ),
        client,
    )


def test_upload_sends_required_multipart_fields_and_timezone() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url == "http://ai.example/api/v1/analyze"
        assert request.headers["content-type"].startswith("multipart/form-data;")
        body = request.read()
        assert b'name="frame"; filename="frame.jpg"' in body
        assert b"Content-Type: image/jpeg" in body
        assert b"jpeg-bytes" in body
        assert b'name="cameraId"' in body
        assert b"cam-001" in body
        assert b'name="capturedAt"' in body
        assert b"2026-08-25T03:10:20.123456+00:00" in body
        return httpx.Response(200, json={"riskLevel": "LOW"})

    uploader, _ = make_uploader(httpx.MockTransport(handler))

    assert uploader.upload(b"jpeg-bytes", CAPTURED_AT) is UploadResult.SUCCESS


@pytest.mark.parametrize(
    ("status_code", "expected"),
    [
        (400, UploadResult.CLIENT_ERROR),
        (413, UploadResult.CLIENT_ERROR),
        (422, UploadResult.CLIENT_ERROR),
        (500, UploadResult.TRANSIENT_ERROR),
    ],
)
def test_upload_discards_client_error_response(
    status_code: int,
    expected: UploadResult,
) -> None:
    uploader, _ = make_uploader(
        httpx.MockTransport(lambda request: httpx.Response(status_code, json={"detail": "bad"}))
    )

    assert uploader.upload(b"frame", CAPTURED_AT) is expected


@pytest.mark.parametrize(
    "error",
    [httpx.ReadTimeout("slow"), httpx.ConnectError("offline")],
)
def test_upload_discards_timeout_or_connection_error(error: httpx.RequestError) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        error.request = request
        raise error

    uploader, _ = make_uploader(httpx.MockTransport(handler))

    assert uploader.upload(b"frame", CAPTURED_AT) is UploadResult.TRANSIENT_ERROR


def test_upload_requires_timezone_aware_capture_time() -> None:
    uploader, _ = make_uploader(
        httpx.MockTransport(lambda request: httpx.Response(200))
    )

    with pytest.raises(ValueError, match="timezone"):
        uploader.upload(b"frame", datetime(2026, 8, 25, 3, 10, 20))


def test_close_closes_http_client() -> None:
    uploader, client = make_uploader(
        httpx.MockTransport(lambda request: httpx.Response(200))
    )

    uploader.close()

    assert client.is_closed


def test_repeated_authentication_error_is_logged_once(
    caplog: pytest.LogCaptureFixture,
) -> None:
    uploader, _ = make_uploader(
        httpx.MockTransport(lambda request: httpx.Response(401, json={"detail": "denied"}))
    )

    uploader.upload(b"first", CAPTURED_AT)
    uploader.upload(b"second", CAPTURED_AT)

    matching_records = [
        record
        for record in caplog.records
        if "rejected uploader configuration" in record.message
    ]
    assert len(matching_records) == 1
