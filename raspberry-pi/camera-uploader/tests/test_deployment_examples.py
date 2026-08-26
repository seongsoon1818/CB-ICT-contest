from pathlib import Path

ENV_PATH = Path(__file__).parents[1] / ".env.example"


def _read_assignments() -> dict[str, str]:
    return {
        key: value
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
        for key, value in [line.split("=", 1)]
    }


def test_example_uses_stable_camera_id_but_requires_the_ai_server_address() -> None:
    assignments = _read_assignments()

    assert assignments["CAMERA_ID"] == "cam-001"
    assert assignments["AI_SERVER_BASE_URL"] == ""


def test_example_keeps_the_approved_capture_defaults() -> None:
    assignments = _read_assignments()

    assert assignments["CAPTURE_FPS"] == "30"
    assert assignments["CAMERA_SOURCE"] == "picamera2"
    assert assignments["CAMERA_DEVICE"] == "/dev/video0"
    assert assignments["FRAME_WIDTH"] == "1280"
    assert assignments["FRAME_HEIGHT"] == "720"
