import builtins
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from animalguard_camera.frame_source import (
    FileFrameSource,
    OpenCvFrameSource,
    Picamera2FrameSource,
)


class FakeOpenCvCapture:
    def __init__(
        self,
        *,
        opened: bool = True,
        read_result: tuple[bool, object | None] = (True, object()),
    ) -> None:
        self.opened = opened
        self.read_result = read_result
        self.set_calls: list[tuple[int, object]] = []
        self.release_calls = 0

    def isOpened(self) -> bool:
        return self.opened

    def set(self, property_id: int, value: object) -> bool:
        self.set_calls.append((property_id, value))
        return True

    def read(self) -> tuple[bool, object | None]:
        return self.read_result

    def release(self) -> None:
        self.release_calls += 1


def install_fake_cv2(
    monkeypatch: pytest.MonkeyPatch,
    capture: FakeOpenCvCapture,
    *,
    encode_result: tuple[bool, object] | None = None,
) -> SimpleNamespace:
    video_capture_calls: list[tuple[str, int]] = []
    encode_calls: list[tuple[str, object]] = []
    configured_encode_result = encode_result or (
        True,
        SimpleNamespace(tobytes=lambda: b"opencv-jpeg"),
    )

    def video_capture(device: str, backend: int) -> FakeOpenCvCapture:
        video_capture_calls.append((device, backend))
        return capture

    def video_writer_fourcc(*characters: str) -> int:
        assert characters == ("M", "J", "P", "G")
        return 1196444237

    def imencode(extension: str, frame: object) -> tuple[bool, object]:
        encode_calls.append((extension, frame))
        return configured_encode_result

    fake_cv2 = SimpleNamespace(
        CAP_V4L2=200,
        CAP_PROP_FRAME_WIDTH=3,
        CAP_PROP_FRAME_HEIGHT=4,
        CAP_PROP_FPS=5,
        CAP_PROP_FOURCC=6,
        CAP_PROP_BUFFERSIZE=38,
        VideoCapture=video_capture,
        VideoWriter_fourcc=video_writer_fourcc,
        imencode=imencode,
        video_capture_calls=video_capture_calls,
        encode_calls=encode_calls,
    )
    monkeypatch.setitem(sys.modules, "cv2", fake_cv2)
    return fake_cv2


def test_file_frame_source_returns_current_file_bytes(tmp_path: Path) -> None:
    frame_path = tmp_path / "frame.jpg"
    source = FileFrameSource(frame_path)
    frame_path.write_bytes(b"first-jpeg")

    assert source.capture_jpeg() == b"first-jpeg"

    frame_path.write_bytes(b"latest-jpeg")
    assert source.capture_jpeg() == b"latest-jpeg"


def test_picamera2_missing_dependency_has_clear_startup_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_import = builtins.__import__

    def reject_picamera2(name: str, *args: object, **kwargs: object) -> object:
        if name == "picamera2":
            raise ImportError("not installed")
        return original_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", reject_picamera2)

    with pytest.raises(RuntimeError, match="Picamera2 is not installed"):
        Picamera2FrameSource()


def test_picamera2_is_initialized_once_captures_jpeg_and_closes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakePicamera2:
        def __init__(self) -> None:
            self.started = False
            self.stopped = False
            self.closed = False
            instances.append(self)

        def create_video_configuration(self, **configuration: object) -> object:
            assert configuration == {
                "main": {"size": (640, 480)},
                "controls": {"FrameRate": 30.0},
            }
            return configuration

        def configure(self, configuration: object) -> None:
            assert configuration == {
                "main": {"size": (640, 480)},
                "controls": {"FrameRate": 30.0},
            }

        def start(self) -> None:
            self.started = True

        def capture_file(self, output: object, *, format: str) -> None:
            assert format == "jpeg"
            output.write(b"jpeg-frame")  # type: ignore[attr-defined]

        def stop(self) -> None:
            self.stopped = True

        def close(self) -> None:
            self.closed = True

    instances: list[FakePicamera2] = []
    monkeypatch.setitem(
        sys.modules,
        "picamera2",
        SimpleNamespace(Picamera2=FakePicamera2),
    )

    source = Picamera2FrameSource(width=640, height=480, target_fps=30.0)

    assert source.capture_jpeg() == b"jpeg-frame"
    assert len(instances) == 1
    source.close()
    source.close()
    assert instances[0].started
    assert instances[0].stopped
    assert instances[0].closed


def test_picamera2_initialization_failure_closes_camera(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    camera = SimpleNamespace(closed=False)

    def create_video_configuration(**configuration: object) -> object:
        return configuration

    def configure(configuration: object) -> None:
        raise OSError("configuration failed")

    def close() -> None:
        camera.closed = True

    camera.create_video_configuration = create_video_configuration
    camera.configure = configure
    camera.close = close
    monkeypatch.setitem(
        sys.modules,
        "picamera2",
        SimpleNamespace(Picamera2=lambda: camera),
    )

    with pytest.raises(RuntimeError, match="Failed to initialize Picamera2"):
        Picamera2FrameSource()

    assert camera.closed


def test_opencv_missing_dependency_has_clear_startup_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_import = builtins.__import__
    monkeypatch.delitem(sys.modules, "cv2", raising=False)

    def reject_cv2(name: str, *args: object, **kwargs: object) -> object:
        if name == "cv2":
            raise ImportError("not installed")
        return original_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", reject_cv2)

    with pytest.raises(RuntimeError, match="python3-opencv"):
        OpenCvFrameSource()


def test_opencv_initializes_once_captures_jpeg_and_closes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    frame = object()
    capture = FakeOpenCvCapture(read_result=(True, frame))
    fake_cv2 = install_fake_cv2(monkeypatch, capture)

    source = OpenCvFrameSource(
        device="/dev/video7",
        width=640,
        height=480,
        target_fps=25.0,
    )

    assert fake_cv2.video_capture_calls == [("/dev/video7", 200)]
    assert capture.set_calls == [
        (6, 1196444237),
        (3, 640),
        (4, 480),
        (5, 25.0),
        (38, 1),
    ]
    assert source.capture_jpeg() == b"opencv-jpeg"
    assert fake_cv2.encode_calls == [(".jpg", frame)]

    source.close()
    source.close()
    assert capture.release_calls == 1
    with pytest.raises(RuntimeError, match="OpenCV source is closed"):
        source.capture_jpeg()


def test_opencv_release_failure_still_marks_source_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FailingReleaseCapture(FakeOpenCvCapture):
        def release(self) -> None:
            self.release_calls += 1
            raise OSError("release failed")

    capture = FailingReleaseCapture()
    install_fake_cv2(monkeypatch, capture)
    source = OpenCvFrameSource()

    with pytest.raises(OSError, match="release failed"):
        source.close()

    source.close()
    assert capture.release_calls == 1
    with pytest.raises(RuntimeError, match="OpenCV source is closed"):
        source.capture_jpeg()


def test_opencv_unopened_device_is_released(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    capture = FakeOpenCvCapture(opened=False)
    install_fake_cv2(monkeypatch, capture)

    with pytest.raises(
        RuntimeError,
        match="Failed to open OpenCV camera device: /dev/video7",
    ):
        OpenCvFrameSource(device="/dev/video7")

    assert capture.release_calls == 1


def test_opencv_capture_failure_has_clear_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    capture = FakeOpenCvCapture(read_result=(False, None))
    install_fake_cv2(monkeypatch, capture)
    source = OpenCvFrameSource(device="/dev/video7")

    with pytest.raises(
        RuntimeError,
        match="Failed to capture frame from OpenCV camera device: /dev/video7",
    ):
        source.capture_jpeg()

    source.close()


def test_opencv_encode_failure_has_clear_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    capture = FakeOpenCvCapture()
    install_fake_cv2(
        monkeypatch,
        capture,
        encode_result=(False, SimpleNamespace(tobytes=lambda: b"")),
    )
    source = OpenCvFrameSource()

    with pytest.raises(RuntimeError, match="Failed to encode OpenCV frame as JPEG"):
        source.capture_jpeg()

    source.close()
