import builtins
from pathlib import Path

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
