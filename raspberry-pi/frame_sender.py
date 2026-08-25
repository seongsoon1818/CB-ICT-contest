#!/usr/bin/env python3
"""
BirdGuard USB webcam JPEG frame sender

?ㅽ뻾 ???섍꼍 蹂???덉떆:
export BIRDGUARD_SERVER_URL="http://192.168.137.10:8000/api/v1/frames"
export BIRDGUARD_DEVICE_ID="birdguard-pi-01"
export BIRDGUARD_DEVICE_TOKEN="replace-with-a-secret"
python3 frame_sender.py
"""

from __future__ import annotations

import logging
import os
import signal
import sys
import time
from datetime import datetime, timezone

import cv2
import requests

SERVER_URL = "http://10.112.89.76:8000/api/v1/frames"
DEVICE_ID = "piseong"
DEVICE_TOKEN = "test1"

CAMERA_INDEX = 0
FRAME_WIDTH = 640
FRAME_HEIGHT = 480
FRAME_INTERVAL_SECONDS = 1.0
JPEG_QUALITY = 80
CONNECT_TIMEOUT_SECONDS = 3
READ_TIMEOUT_SECONDS = 5

running = True


def stop_sender(signum: int, frame: object) -> None:
    """Ctrl+C ?먮뒗 ?쒕퉬??以묒? ?붿껌??諛쏆븘 諛섎났???앸궦??"""
    global running
    del signum, frame
    running = False
    

def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def open_camera() -> cv2.VideoCapture:
    # Linux/Raspberry Pi OS??USB UVC 移대찓?쇱슜 V4L2 諛깆뿏??

    camera = cv2.VideoCapture(CAMERA_INDEX, cv2.CAP_V4L2)
    if not camera.isOpened():
        raise RuntimeError(
            f"?뱀틺???????놁뒿?덈떎. index={CAMERA_INDEX}; "
            "v4l2-ctl --list-devices 寃곌낵? USB ?곌껐???뺤씤?섏꽭??"
        )
        
    camera.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    camera.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
    camera.set(cv2.CAP_PROP_BUFFERSIZE, 1)

    # ?먮룞 ?몄텧怨?珥덉젏 ?덉젙???쒓컙.
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
    headers = {}
    if DEVICE_TOKEN:
        headers["X-Device-Token"] = DEVICE_TOKEN

    filename = f"{DEVICE_ID}-{sequence:06d}.jpg"
    files = {"image": (filename, image_bytes, "image/jpeg")}
    data = {
        "device_id": DEVICE_ID,
        "captured_at": captured_at,
        "sequence": str(sequence),
    }
    response = session.post(
        SERVER_URL,
        headers=headers,
        files=files,
        data=data,
        timeout=(CONNECT_TIMEOUT_SECONDS, READ_TIMEOUT_SECONDS),
    )
    response.raise_for_status()

    try:
        response_body = response.json()
    except ValueError:
        response_body = {"raw_response": response.text[:200]}
    logging.info(
        "?꾩넚 ?깃났 sequence=%s status=%s response=%s",
        sequence,
        response.status_code,
        response_body,
    )

def main() -> int:
    if not SERVER_URL.startswith(("http://", "https://")):
        logging.error("BIRDGUARD_SERVER_URL? http:// ?먮뒗 https://濡??쒖옉?댁빞 ?⑸땲??")
        return 2
    if FRAME_INTERVAL_SECONDS <= 0:
        logging.error("BIRDGUARD_FRAME_INTERVAL_SECONDS??0蹂대떎 而ㅼ빞 ?⑸땲??")
        return 2
    if not 1 <= JPEG_QUALITY <= 100:
        logging.error("BIRDGUARD_JPEG_QUALITY??1~100 踰붿쐞?댁빞 ?⑸땲??")
        return 2

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
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
        "?꾨젅???꾩넚 ?쒖옉 server=%s device_id=%s camera=%s",
        SERVER_URL,
        DEVICE_ID,
        CAMERA_INDEX,
    )

    try:
        while running:
            now = time.monotonic()
            if now < next_capture_at:
                time.sleep(min(0.05, next_capture_at - now))
                continue

            # 泥섎━ 吏?곗씠 ?앷꺼??怨쇨굅 ?꾨젅?꾩쓣 ?곕씪?≪? ?딄퀬 理쒖떊 ?꾨젅?꾨쭔 蹂대깂.
            next_capture_at = time.monotonic() + FRAME_INTERVAL_SECONDS
            captured_at = utc_now_iso()
            ok, frame = camera.read()
            if not ok:
                logging.warning("?뱀틺 ?꾨젅?꾩쓣 ?쎌? 紐삵뻽?듬땲??")
                continue

            encoded, jpeg = cv2.imencode(
                ".jpg",
                frame,
                [int(cv2.IMWRITE_JPEG_QUALITY), JPEG_QUALITY],
            )
            if not encoded:
                logging.warning("JPEG ?몄퐫?⑹뿉 ?ㅽ뙣?덉뒿?덈떎.")
                continue

            sequence += 1
            try:
                post_frame(session, jpeg.tobytes(), captured_at, sequence)
            except requests.RequestException as error:
                logging.warning("?꾩넚 ?ㅽ뙣 sequence=%s error=%s", sequence, error)
    finally:
        camera.release()
        session.close()
        logging.info("?꾨젅???꾩넚??醫낅즺?덉뒿?덈떎.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
