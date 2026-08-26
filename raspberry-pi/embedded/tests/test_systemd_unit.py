from pathlib import Path

UNIT_PATH = (
    Path(__file__).parents[1]
    / "systemd"
    / "animalguard-embedded.service"
)


def test_systemd_unit_uses_the_documented_runtime_paths_and_gpio_group() -> None:
    unit = UNIT_PATH.read_text(encoding="utf-8")

    assert "User=animalguard" in unit
    assert "SupplementaryGroups=gpio" in unit
    assert "WorkingDirectory=/opt/animalguard/embedded" in unit
    assert "EnvironmentFile=/etc/animalguard/embedded.env" in unit
    assert (
        "ExecStart=/opt/animalguard/embedded/.venv/bin/python "
        "-m animalguard_embedded.main"
    ) in unit


def test_systemd_unit_creates_private_state_and_bounds_restart_and_shutdown() -> None:
    unit = UNIT_PATH.read_text(encoding="utf-8")

    assert "StateDirectory=animalguard" in unit
    assert "StateDirectoryMode=0750" in unit
    assert "UMask=0077" in unit
    assert "StartLimitIntervalSec=60" in unit
    assert "StartLimitBurst=3" in unit
    assert "Restart=on-failure" in unit
    assert "RestartSec=5" in unit
    assert "KillSignal=SIGTERM" in unit
    assert "TimeoutStopSec=15" in unit
