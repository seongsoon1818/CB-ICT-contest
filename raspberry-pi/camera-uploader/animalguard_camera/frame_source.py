from contextlib import suppress
from io import BytesIO
from pathlib import Path
from typing import Any, Protocol


class FrameSource(Protocol):
    def capture_jpeg(self) -> bytes: ...

    def close(self) -> None: ...


class FileFrameSource:
    def __init__(self, path: Path) -> None:
        self._path = path

    def capture_jpeg(self) -> bytes:
        return self._path.read_bytes()

    def close(self) -> None:
        pass


class OpenCvFrameSource:
    def __init__(
        self,
        device: str = "/dev/video0",
        width: int = 1280,
        height: int = 720,
        target_fps: float = 30.0,
    ) -> None:
        try:
            import cv2
        except ImportError as error:
            raise RuntimeError(
                "OpenCV is not installed. Install the Raspberry Pi OS "
                "python3-opencv package before using CAMERA_SOURCE=opencv."
            ) from error

        capture: Any | None = None
        try:
            capture = cv2.VideoCapture(device, cv2.CAP_V4L2)
            if not capture.isOpened():
                raise RuntimeError(f"Failed to open OpenCV camera device: {device}")
            capture.set(
                cv2.CAP_PROP_FOURCC,
                cv2.VideoWriter_fourcc(*"MJPG"),
            )
            capture.set(cv2.CAP_PROP_FRAME_WIDTH, width)
            capture.set(cv2.CAP_PROP_FRAME_HEIGHT, height)
            capture.set(cv2.CAP_PROP_FPS, target_fps)
            capture.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        except Exception:
            if capture is not None:
                with suppress(Exception):
                    capture.release()
            raise

        self._cv2: Any | None = cv2
        self._capture: Any | None = capture
        self._device = device

    def capture_jpeg(self) -> bytes:
        if self._capture is None or self._cv2 is None:
            raise RuntimeError("OpenCV source is closed")

        captured, frame = self._capture.read()
        if not captured or frame is None:
            raise RuntimeError(
                "Failed to capture frame from OpenCV camera device: "
                f"{self._device}"
            )

        encoded, jpeg = self._cv2.imencode(".jpg", frame)
        if not encoded:
            raise RuntimeError("Failed to encode OpenCV frame as JPEG")
        return jpeg.tobytes()

    def close(self) -> None:
        if self._capture is None:
            return
        capture = self._capture
        self._capture = None
        self._cv2 = None
        capture.release()


class Picamera2FrameSource:
    def __init__(
        self,
        width: int = 1280,
        height: int = 720,
        target_fps: float = 30.0,
    ) -> None:
        try:
            from picamera2 import Picamera2
        except ImportError as error:
            raise RuntimeError(
                "Picamera2 is not installed. Install the Raspberry Pi OS "
                "python3-picamera2 package before using CAMERA_SOURCE=picamera2."
            ) from error

        camera: Any | None = None
        try:
            camera = Picamera2()
            camera.configure(
                camera.create_video_configuration(
                    main={"size": (width, height)},
                    controls={"FrameRate": target_fps},
                )
            )
            camera.start()
        except Exception as error:
            if camera is not None:
                with suppress(Exception):
                    camera.close()
            raise RuntimeError(f"Failed to initialize Picamera2: {error}") from error
        self._camera: Any | None = camera

    def capture_jpeg(self) -> bytes:
        if self._camera is None:
            raise RuntimeError("Picamera2 source is closed")
        output = BytesIO()
        self._camera.capture_file(output, format="jpeg")
        return output.getvalue()

    def close(self) -> None:
        if self._camera is None:
            return
        try:
            self._camera.stop()
        finally:
            self._camera.close()
            self._camera = None
