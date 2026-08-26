import hashlib
import json
import os
import threading
import time
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from app.schemas import DetectionEvent
from app.settings import FrameEvidenceSettings


class RollingFrameEvidenceStore:
    def __init__(
        self,
        settings: FrameEvidenceSettings,
        *,
        monotonic: Callable[[], float] = time.monotonic,
        now: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        if settings.mode != "rolling" or settings.directory is None:
            raise ValueError(
                "RollingFrameEvidenceStore requires rolling mode and a directory"
            )
        self._root = settings.directory
        self._max_files_per_camera = settings.max_files_per_camera
        self._min_interval_seconds = settings.min_interval_seconds
        self._max_bytes_per_camera = settings.max_bytes_per_camera
        self._monotonic = monotonic
        self._now = now
        self._lock = threading.Lock()
        self._last_saved_at: dict[str, float] = {}

        if self._root.is_symlink():
            raise OSError("Frame evidence root must not be a symlink")
        self._root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self._root.is_symlink():
            raise OSError("Frame evidence root must not be a symlink")
        if not self._root.is_dir():
            raise NotADirectoryError(
                f"Frame evidence root is not a directory: {self._root}"
            )
        self._root.chmod(0o700)
        for child in self._root.iterdir():
            if child.is_dir() and not child.is_symlink():
                self._prune(child)

    def record(self, frame_bytes: bytes, event: DetectionEvent) -> bool:
        with self._lock:
            current_monotonic = self._monotonic()
            last_saved = self._last_saved_at.get(event.cameraId)
            if (
                last_saved is not None
                and current_monotonic - last_saved < self._min_interval_seconds
            ):
                return False

            saved_at = self._now().astimezone(UTC)
            camera_directory = self._camera_directory(event.cameraId)
            stem = (
                f"{saved_at.strftime('%Y%m%dT%H%M%S.%fZ')}_"
                f"{event.eventId}"
            )
            self._write_pair(
                camera_directory,
                stem,
                frame_bytes,
                event,
                saved_at,
            )
            kept = self._prune(camera_directory, newest_stem=stem)
            if kept:
                self._last_saved_at[event.cameraId] = current_monotonic
            return kept

    def _camera_directory(self, camera_id: str) -> Path:
        camera_directory = self._root / camera_id
        if camera_directory.is_symlink():
            raise OSError(
                f"Frame evidence camera directory must not be a symlink: {camera_id}"
            )
        camera_directory.mkdir(mode=0o700, exist_ok=True)
        camera_directory.chmod(0o700)
        return camera_directory

    def _write_pair(
        self,
        camera_directory: Path,
        stem: str,
        frame_bytes: bytes,
        event: DetectionEvent,
        saved_at: datetime,
    ) -> None:
        jpeg_path = camera_directory / f"{stem}.jpg"
        json_path = camera_directory / f"{stem}.json"
        temporary_id = uuid4()
        jpeg_temporary = camera_directory / f".{stem}.{temporary_id}.jpg.tmp"
        json_temporary = camera_directory / f".{stem}.{temporary_id}.json.tmp"

        payload = {
            "reason": "ROLLING_REALTIME",
            "savedAt": saved_at.isoformat().replace("+00:00", "Z"),
            "sha256": hashlib.sha256(frame_bytes).hexdigest(),
            **event.model_dump(mode="json"),
        }
        json_bytes = json.dumps(
            payload,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")

        try:
            self._write_private_file(json_temporary, json_bytes)
            self._write_private_file(jpeg_temporary, frame_bytes)
            os.replace(json_temporary, json_path)
            os.replace(jpeg_temporary, jpeg_path)
        except Exception:
            jpeg_temporary.unlink(missing_ok=True)
            json_temporary.unlink(missing_ok=True)
            if json_path.exists() and not jpeg_path.exists():
                json_path.unlink(missing_ok=True)
            raise

    @staticmethod
    def _write_private_file(path: Path, contents: bytes) -> None:
        descriptor = os.open(
            path,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o600,
        )
        with os.fdopen(descriptor, "wb") as output:
            output.write(contents)

    def _prune(
        self,
        camera_directory: Path,
        *,
        newest_stem: str | None = None,
    ) -> bool:
        self._remove_incomplete_files(camera_directory)
        pairs = self._complete_pairs(camera_directory)
        total_bytes = sum(
            jpeg.stat().st_size + sidecar.stat().st_size
            for jpeg, sidecar in pairs
        )

        while pairs and (
            len(pairs) > self._max_files_per_camera
            or total_bytes > self._max_bytes_per_camera
        ):
            jpeg, sidecar = pairs.pop(0)
            pair_size = jpeg.stat().st_size + sidecar.stat().st_size
            jpeg.unlink(missing_ok=True)
            sidecar.unlink(missing_ok=True)
            total_bytes -= pair_size

        if newest_stem is None:
            return False
        return any(jpeg.stem == newest_stem for jpeg, _ in pairs)

    @staticmethod
    def _remove_incomplete_files(camera_directory: Path) -> None:
        for temporary in camera_directory.glob("*.tmp"):
            temporary.unlink(missing_ok=True)

        jpeg_by_stem = {
            path.stem: path for path in camera_directory.glob("*.jpg")
        }
        json_by_stem = {
            path.stem: path for path in camera_directory.glob("*.json")
        }
        for stem in jpeg_by_stem.keys() - json_by_stem.keys():
            jpeg_by_stem[stem].unlink(missing_ok=True)
        for stem in json_by_stem.keys() - jpeg_by_stem.keys():
            json_by_stem[stem].unlink(missing_ok=True)

    @staticmethod
    def _complete_pairs(camera_directory: Path) -> list[tuple[Path, Path]]:
        pairs = []
        for jpeg in camera_directory.glob("*.jpg"):
            sidecar = jpeg.with_suffix(".json")
            if sidecar.is_file():
                pairs.append((jpeg, sidecar))
        return sorted(pairs, key=lambda pair: pair[0].name)
