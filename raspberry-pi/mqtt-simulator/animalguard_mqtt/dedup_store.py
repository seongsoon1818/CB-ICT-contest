from __future__ import annotations

import json
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from threading import Lock
from typing import Any


@dataclass(frozen=True)
class ProcessedCommand:
    command_id: str
    device_id: str
    status: str
    processed_at: str
    ack_payload: dict[str, Any]


class DedupStore:
    def __init__(self, database_path: str | Path):
        self.database_path = Path(database_path)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = Lock()
        self._connection = sqlite3.connect(
            self.database_path, check_same_thread=False
        )
        self._connection.row_factory = sqlite3.Row
        self._create_table()

    def _create_table(self) -> None:
        with self._connection:
            self._connection.execute(
                """
                CREATE TABLE IF NOT EXISTS processed_commands (
                    command_id TEXT PRIMARY KEY,
                    device_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    processed_at TEXT NOT NULL,
                    ack_payload TEXT NOT NULL
                )
                """
            )

    def reserve(
        self,
        command_id: str,
        device_id: str,
        status: str,
        processed_at: str,
        ack_payload: dict[str, Any],
    ) -> bool:
        with self._lock, self._connection:
            try:
                self._connection.execute(
                    """
                    INSERT INTO processed_commands (
                        command_id, device_id, status, processed_at, ack_payload
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    (
                        command_id,
                        device_id,
                        status,
                        processed_at,
                        json.dumps(ack_payload, separators=(",", ":")),
                    ),
                )
            except sqlite3.IntegrityError:
                return False
        return True

    def update(
        self,
        command_id: str,
        status: str,
        processed_at: str,
        ack_payload: dict[str, Any],
    ) -> None:
        with self._lock, self._connection:
            cursor = self._connection.execute(
                """
                UPDATE processed_commands
                SET status = ?, processed_at = ?, ack_payload = ?
                WHERE command_id = ?
                """,
                (
                    status,
                    processed_at,
                    json.dumps(ack_payload, separators=(",", ":")),
                    command_id,
                ),
            )
            if cursor.rowcount != 1:
                raise KeyError(f"commandId is not reserved: {command_id}")

    def get(self, command_id: str) -> ProcessedCommand | None:
        with self._lock:
            row = self._connection.execute(
                """
                SELECT command_id, device_id, status, processed_at, ack_payload
                FROM processed_commands
                WHERE command_id = ?
                """,
                (command_id,),
            ).fetchone()
        if row is None:
            return None
        return ProcessedCommand(
            command_id=row["command_id"],
            device_id=row["device_id"],
            status=row["status"],
            processed_at=row["processed_at"],
            ack_payload=json.loads(row["ack_payload"]),
        )

    def close(self) -> None:
        with self._lock:
            self._connection.close()
