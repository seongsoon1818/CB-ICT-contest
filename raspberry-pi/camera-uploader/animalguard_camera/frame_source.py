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


class Picamera2FrameSource:
    def __init__(self, width: int = 1280, height: int = 720) -> None:
        try:
            from picamera2 import Picamera2
        except ImportError as error:
            raise RuntimeError(
                "Picamera2 is not installed. Install the Raspberry Pi OS "
                "python3-picamera2 package before using CAMERA_SOURCE=picamera2."
            ) from error

        try:
            camera = Picamera2()
            camera.configure(
                camera.create_still_configuration(main={"size": (width, height)})
            )
            camera.start()
        except Exception as error:
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
