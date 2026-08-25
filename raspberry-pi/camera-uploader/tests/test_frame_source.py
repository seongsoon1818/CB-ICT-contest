import builtins
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

from animalguard_camera.frame_source import FileFrameSource, Picamera2FrameSource


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
    instances: list[FakePicamera2] = []

    class FakePicamera2:
        def __init__(self) -> None:
            self.started = False
            self.stopped = False
            self.closed = False
            instances.append(self)

        def create_still_configuration(self, **configuration: object) -> object:
            assert configuration == {"main": {"size": (640, 480)}}
            return configuration

        def configure(self, configuration: object) -> None:
            assert configuration == {"main": {"size": (640, 480)}}

        def start(self) -> None:
            self.started = True

        def capture_file(self, output: object, *, format: str) -> None:
            assert format == "jpeg"
            output.write(b"jpeg-frame")  # type: ignore[attr-defined]

        def stop(self) -> None:
            self.stopped = True

        def close(self) -> None:
            self.closed = True

    monkeypatch.setitem(
        sys.modules,
        "picamera2",
        SimpleNamespace(Picamera2=FakePicamera2),
    )

    source = Picamera2FrameSource(width=640, height=480)

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

    def create_still_configuration(**configuration: object) -> object:
        return configuration

    def configure(configuration: object) -> None:
        raise OSError("configuration failed")

    def close() -> None:
        camera.closed = True

    camera.create_still_configuration = create_still_configuration
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
