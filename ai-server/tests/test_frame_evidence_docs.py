from pathlib import Path

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
AI_SERVER_README = REPOSITORY_ROOT / "ai-server" / "README.md"
ENV_EXAMPLE = REPOSITORY_ROOT / ".env.example"
GITIGNORE = REPOSITORY_ROOT / ".gitignore"

EVIDENCE_VARIABLES = {
    "FRAME_EVIDENCE_MODE",
    "FRAME_EVIDENCE_DIR",
    "FRAME_EVIDENCE_MAX_FILES_PER_CAMERA",
    "FRAME_EVIDENCE_MIN_INTERVAL_SECONDS",
    "FRAME_EVIDENCE_MAX_BYTES_PER_CAMERA",
}


def test_environment_example_keeps_frame_evidence_disabled_and_bounded() -> None:
    contents = ENV_EXAMPLE.read_text(encoding="utf-8")

    for variable in EVIDENCE_VARIABLES:
        assert f"{variable}=" in contents
    assert "FRAME_EVIDENCE_MODE=off" in contents
    assert "FRAME_EVIDENCE_MAX_FILES_PER_CAMERA=60" in contents
    assert "FRAME_EVIDENCE_MIN_INTERVAL_SECONDS=1" in contents
    assert "FRAME_EVIDENCE_MAX_BYTES_PER_CAMERA=104857600" in contents


def test_readme_documents_rolling_pairs_pruning_and_mock_limit() -> None:
    contents = AI_SERVER_README.read_text(encoding="utf-8")

    for variable in EVIDENCE_VARIABLES:
        assert variable in contents
    assert "JPEG/JSON" in contents
    assert "카메라별" in contents
    assert "오래된" in contents
    assert "Mock" in contents
    assert "실제 모델" in contents


def test_repository_ignores_local_frame_evidence_directory() -> None:
    contents = GITIGNORE.read_text(encoding="utf-8")

    assert "frame-evidence/" in contents.splitlines()
