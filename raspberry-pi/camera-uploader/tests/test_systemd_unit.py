from pathlib import Path


UNIT_PATH = (
    Path(__file__).parents[1]
    / "systemd"
    / "animalguard-camera-uploader.service"
)


def test_systemd_unit_limits_restart_loop_and_grants_camera_group() -> None:
    unit = UNIT_PATH.read_text(encoding="utf-8")

    assert "StartLimitIntervalSec=60" in unit
    assert "StartLimitBurst=3" in unit
    assert "SupplementaryGroups=video" in unit
    assert "Restart=on-failure" in unit
