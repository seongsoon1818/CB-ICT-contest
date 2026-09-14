import hashlib
import json
import stat
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import UUID

import pytest

from app.frame_evidence import RollingFrameEvidenceStore
from app.schemas import Bbox, Detection, DetectionEvent, ImageInfo, ModelInfo
from app.settings import FrameEvidenceSettings

BASE_TIME = datetime(2026, 8, 27, tzinfo=UTC)


class SequenceClock:
    def __init__(self, values):
        self._values = iter(values)

    def __call__(self):
        return next(self._values)


def make_event(
    index: int,
    *,
    camera_id: str = "cam-001",
) -> DetectionEvent:
    return DetectionEvent(
        eventId=UUID(int=index),
        cameraId=camera_id,
        capturedAt=BASE_TIME + timedelta(seconds=index),
        image=ImageInfo(width=640, height=480),
        model=ModelInfo(
            detectorVersion="mock-animal-detector-v1",
            classifierVersion=None,
        ),
        detections=[
            Detection(
                detectionId=f"detection-{index}",
                trackId=None,
                classCode="MAGPIE",
                detectionConfidence=0.95,
                classificationConfidence=None,
                bbox=Bbox(x=10, y=20, width=30, height=40),
            )
        ],
    )


def make_backend_analysis(event: DetectionEvent) -> dict[str, object]:
    return {
        "eventId": str(event.eventId),
        "riskScore": 50,
        "riskLevel": "MEDIUM",
        "commandOutcome": "NOT_REQUESTED",
        "commandBlockers": [],
    }


def make_settings(
    directory: Path,
    *,
    max_files: int = 60,
    min_interval: float = 0.0,
    max_bytes: int = 100 * 1024 * 1024,
) -> FrameEvidenceSettings:
    return FrameEvidenceSettings(
        mode="rolling",
        directory=directory,
        max_files_per_camera=max_files,
        min_interval_seconds=min_interval,
        max_bytes_per_camera=max_bytes,
    )


def complete_pairs(camera_directory: Path) -> list[tuple[Path, Path]]:
    return [
        (jpeg, jpeg.with_suffix(".json"))
        for jpeg in sorted(camera_directory.glob("*.jpg"))
        if jpeg.with_suffix(".json").is_file()
    ]


def test_record_writes_exact_jpeg_and_detection_event_sidecar(
    tmp_path: Path,
) -> None:
    frame_bytes = b"exact-jpeg-bytes"
    event = make_event(1)
    store = RollingFrameEvidenceStore(
        make_settings(tmp_path),
        monotonic=SequenceClock([1.0]),
        now=SequenceClock([BASE_TIME]),
    )

    backend_analysis = make_backend_analysis(event)

    assert store.record(frame_bytes, event, backend_analysis)

    pairs = complete_pairs(tmp_path / "cam-001")
    assert len(pairs) == 1
    jpeg_path, json_path = pairs[0]
    assert jpeg_path.read_bytes() == frame_bytes
    payload = json.loads(json_path.read_text(encoding="utf-8"))
    assert payload["reason"] == "ROLLING_REALTIME"
    assert payload["savedAt"] == "2026-08-27T00:00:00Z"
    assert payload["sha256"] == hashlib.sha256(frame_bytes).hexdigest()
    assert payload["eventId"] == str(event.eventId)
    assert payload["cameraId"] == "cam-001"
    assert payload["detections"][0]["classCode"] == "MAGPIE"
    assert payload["backendAnalysis"] == backend_analysis
    assert payload["detections"][0]["bbox"] == {
        "x": 10,
        "y": 20,
        "width": 30,
        "height": 40,
    }
    assert stat.S_IMODE(tmp_path.stat().st_mode) == 0o700
    assert stat.S_IMODE((tmp_path / "cam-001").stat().st_mode) == 0o700
    assert stat.S_IMODE(jpeg_path.stat().st_mode) == 0o600
    assert stat.S_IMODE(json_path.stat().st_mode) == 0o600


def test_record_rate_limits_each_camera_independently(tmp_path: Path) -> None:
    store = RollingFrameEvidenceStore(
        make_settings(tmp_path, min_interval=1.0),
        monotonic=SequenceClock([0.0, 0.5, 0.5, 1.0]),
        now=SequenceClock(
            [
                BASE_TIME,
                BASE_TIME + timedelta(milliseconds=500),
                BASE_TIME + timedelta(seconds=1),
            ]
        ),
    )

    first = make_event(1, camera_id="cam-001")
    skipped = make_event(2, camera_id="cam-001")
    other = make_event(3, camera_id="cam-002")
    second = make_event(4, camera_id="cam-001")

    assert store.record(b"first", first, make_backend_analysis(first))
    assert not store.record(
        b"skipped",
        skipped,
        make_backend_analysis(skipped),
    )
    assert store.record(b"other", other, make_backend_analysis(other))
    assert store.record(b"second", second, make_backend_analysis(second))

    assert len(complete_pairs(tmp_path / "cam-001")) == 2
    assert len(complete_pairs(tmp_path / "cam-002")) == 1


def test_record_prunes_oldest_complete_pairs_by_count(tmp_path: Path) -> None:
    store = RollingFrameEvidenceStore(
        make_settings(tmp_path, max_files=2),
        monotonic=SequenceClock([0.0, 1.0, 2.0]),
        now=SequenceClock(
            [
                BASE_TIME,
                BASE_TIME + timedelta(seconds=1),
                BASE_TIME + timedelta(seconds=2),
            ]
        ),
    )

    for index in range(1, 4):
        event = make_event(index)
        assert store.record(
            f"jpeg-{index}".encode(),
            event,
            make_backend_analysis(event),
        )

    pairs = complete_pairs(tmp_path / "cam-001")
    assert len(pairs) == 2
    event_ids = {
        json.loads(json_path.read_text(encoding="utf-8"))["eventId"]
        for _, json_path in pairs
    }
    assert event_ids == {str(UUID(int=2)), str(UUID(int=3))}


def test_record_prunes_oldest_complete_pairs_by_total_bytes(
    tmp_path: Path,
) -> None:
    first_store = RollingFrameEvidenceStore(
        make_settings(tmp_path),
        monotonic=SequenceClock([0.0]),
        now=SequenceClock([BASE_TIME]),
    )
    first_event = make_event(1)
    assert first_store.record(
        b"first-frame",
        first_event,
        make_backend_analysis(first_event),
    )
    camera_directory = tmp_path / "cam-001"
    first_pair_size = sum(
        path.stat().st_size
        for pair in complete_pairs(camera_directory)
        for path in pair
    )

    bounded_store = RollingFrameEvidenceStore(
        make_settings(tmp_path, max_bytes=first_pair_size + 32),
        monotonic=SequenceClock([1.0]),
        now=SequenceClock([BASE_TIME + timedelta(seconds=1)]),
    )
    second_event = make_event(2)
    assert bounded_store.record(
        b"second-frame",
        second_event,
        make_backend_analysis(second_event),
    )

    pairs = complete_pairs(camera_directory)
    assert len(pairs) == 1
    payload = json.loads(pairs[0][1].read_text(encoding="utf-8"))
    assert payload["eventId"] == str(UUID(int=2))


def test_store_startup_removes_incomplete_files_and_prunes_existing_pairs(
    tmp_path: Path,
) -> None:
    initial_store = RollingFrameEvidenceStore(
        make_settings(tmp_path),
        monotonic=SequenceClock([0.0, 1.0]),
        now=SequenceClock([BASE_TIME, BASE_TIME + timedelta(seconds=1)]),
    )
    first_event = make_event(1)
    second_event = make_event(2)
    assert initial_store.record(
        b"first",
        first_event,
        make_backend_analysis(first_event),
    )
    assert initial_store.record(
        b"second",
        second_event,
        make_backend_analysis(second_event),
    )
    camera_directory = tmp_path / "cam-001"
    (camera_directory / "orphan.jpg").write_bytes(b"orphan")
    (camera_directory / "sidecar-only.json").write_text("{}", encoding="utf-8")
    (camera_directory / "interrupted.jpg.tmp").write_bytes(b"partial")

    RollingFrameEvidenceStore(make_settings(tmp_path, max_files=1))

    pairs = complete_pairs(camera_directory)
    assert len(pairs) == 1
    payload = json.loads(pairs[0][1].read_text(encoding="utf-8"))
    assert payload["eventId"] == str(UUID(int=2))
    assert not (camera_directory / "orphan.jpg").exists()
    assert not (camera_directory / "sidecar-only.json").exists()
    assert not (camera_directory / "interrupted.jpg.tmp").exists()


def test_store_rejects_symlink_evidence_root(tmp_path: Path) -> None:
    outside_directory = tmp_path / "outside"
    outside_directory.mkdir()
    evidence_root = tmp_path / "evidence-link"
    evidence_root.symlink_to(outside_directory, target_is_directory=True)

    with pytest.raises(OSError, match="root must not be a symlink"):
        RollingFrameEvidenceStore(make_settings(evidence_root))

    assert list(outside_directory.iterdir()) == []


def test_store_rejects_symlink_camera_directory(tmp_path: Path) -> None:
    evidence_root = tmp_path / "evidence"
    store = RollingFrameEvidenceStore(make_settings(evidence_root))
    outside_directory = tmp_path / "outside"
    outside_directory.mkdir()
    (evidence_root / "cam-001").symlink_to(
        outside_directory,
        target_is_directory=True,
    )
    event = make_event(1)

    with pytest.raises(OSError, match="camera directory must not be a symlink"):
        store.record(b"frame", event, make_backend_analysis(event))

    assert list(outside_directory.iterdir()) == []
