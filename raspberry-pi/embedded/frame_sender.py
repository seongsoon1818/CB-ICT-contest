#!/usr/bin/env python3
"""BirdGuard USB webcam JPEG frame sender."""

from __future__ import annotations

import logging
import signal
import sys
import time
from datetime import datetime, timezone

import cv2
import requests


SERVER_URL = "http://10.112.89.131:8000/api/v1/analyze"
CAMERA_ID = "piseong"
CAMERA_INDEX = 0
FRAME_WIDTH = 640
FRAME_HEIGHT = 480
FRAME_INTERVAL_SECONDS = 1.0
JPEG_QUALITY = 80
CONNECT_TIMEOUT_SECONDS = 3
READ_TIMEOUT_SECONDS = 5

running = True


def stop_sender(signum: int, frame: object) -> None:
    """Stop the capture loop after Ctrl+C or a service stop request."""
    global running
    del signum, frame
    running = False


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def open_camera() -> cv2.VideoCapture:
    """Open the USB UVC webcam through the Raspberry Pi V4L2 backend."""
    camera = cv2.VideoCapture(CAMERA_INDEX, cv2.CAP_V4L2)
    if not camera.isOpened():
        raise RuntimeError(
            f"Cannot open webcam at index {CAMERA_INDEX}. "
            "Check the USB connection and v4l2-ctl --list-devices output."
        )

    camera.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    camera.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
    camera.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    for _ in range(10):
        camera.read()
        time.sleep(0.05)
    return camera


def post_frame(
    session: requests.Session,
    image_bytes: bytes,
    captured_at: str,
    sequence: int,
) -> None:
    """Upload one JPEG frame using the AI server multipart contract."""
    filename = f"{CAMERA_ID}-{sequence:06d}.jpg"
    response = session.post(
        SERVER_URL,
        files={"frame": (filename, image_bytes, "image/jpeg")},
        data={
            "cameraId": CAMERA_ID,
            "capturedAt": captured_at,
        },
        timeout=(CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS),
    )
    response.raise_for_status()

    try:
        response_body = response.json()
    except ValueError:
        response_body = {"response_bytes": len(response.content)}
    logging.info(
        "Frame upload succeeded sequence=%s status=%s response=%s",
        sequence,
        response.status_code,
        response_body,
    )


def validate_settings() -> None:
    if not SERVER_URL.startswith(("http://", "https://")):
        raise ValueError("SERVER_URL must start with http:// or https://")
    if not CAMERA_ID:
        raise ValueError("CAMERA_ID must not be empty")
    if FRAME_INTERVAL_SECONDS <= 0:
        raise ValueError("BIRDGUARD_FRAME_INTERVAL_SECONDS must be greater than zero")
    if not 1 <= JPEG_QUALITY <= 100:
        raise ValueError("BIRDGUARD_JPEG_QUALITY must be between 1 and 100")


def main() -> int:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )

    try:
        validate_settings()
    except ValueError as error:
        logging.error("Invalid configuration: %s", error)
        return 2

    signal.signal(signal.SIGINT, stop_sender)
    signal.signal(signal.SIGTERM, stop_sender)

    try:
        camera = open_camera()
    except RuntimeError as error:
        logging.error("%s", error)
        return 1

    session = requests.Session()
    sequence = 0
    next_capture_at = time.monotonic()
    logging.info(
        "Frame sender started server=%s camera_id=%s camera_index=%s",
        SERVER_URL,
        CAMERA_ID,
        CAMERA_INDEX,
    )

    try:
        while running:
            now = time.monotonic()
            if now < next_capture_at:
                time.sleep(min(0.05, next_capture_at - now))
                continue

            # Keep only the newest frame when capture or upload is delayed.
            next_capture_at = time.monotonic() + FRAME_INTERVAL_SECONDS
            captured_at = utc_now_iso()
            ok, frame = camera.read()
            if not ok:
                logging.warning("Failed to read a webcam frame")
                continue

            encoded, jpeg = cv2.imencode(
                ".jpg",
                frame,
                [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY],
            )
            if not encoded:
                logging.warning("JPEG encoding failed")
                continue

            sequence += 1
            try:
                post_frame(session, jpeg.tobytes(), captured_at, sequence)
            except requests.RequestException as error:
                logging.warning(
                    "Frame upload failed sequence=%s error=%s",
                    sequence,
                    error,
                )
    finally:
        camera.release()
        session.close()
        logging.info("Frame sender stopped")

    return 0


if __name__ == "__main__":
    sys.exit(main())