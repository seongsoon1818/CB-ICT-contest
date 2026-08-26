#!/usr/bin/env python3
"""Verify the isolated AnimalGuard Mock E2E against public APIs and test DBs."""

from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import time
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from io import BytesIO
from pathlib import Path
from typing import Any, Callable
from uuid import uuid4

import httpx
from PIL import Image


DEFAULT_WAIT_SECONDS = 30.0
COMMAND_COOLDOWN_SECONDS = 1.0
COMMAND_TTL_SECONDS = 1.0


class E2EVerificationError(AssertionError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise E2EVerificationError(message)


def sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def rfc3339(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def parse_database_timestamp(value: str) -> str:
    parsed = datetime.fromisoformat(value.replace(" ", "T"))
    return rfc3339(parsed)


@dataclass(frozen=True)
class CommandRow:
    command_id: str
    event_id: str | None
    device_id: str
    source: str
    command_type: str
    duration_ms: int | None
    reason: str
    issued_at: str
    expires_at: str
    status: str


@dataclass(frozen=True)
class ObservationRow:
    presence_state: str
    sound_alert_command_id: str | None
    deterrent_full_command_id: str | None
    absence_started_at: str | None


class MockE2E:
    def __init__(self, args: argparse.Namespace) -> None:
        self.backend_url = args.backend_url.rstrip("/")
        self.ai_detected_url = args.ai_detected_url.rstrip("/")
        self.ai_empty_url = args.ai_empty_url.rstrip("/")
        self.compose_prefix = [
            "docker",
            "compose",
            "--project-name",
            args.compose_project,
            "-f",
            str(args.compose_file),
        ]
        self.operator_token = args.operator_token
        self.simulator_pid = args.simulator_pid
        self.simulator_log = args.simulator_log
        self.mqtt_drop_file = args.mqtt_drop_file
        self.http = httpx.Client(timeout=10.0)
        self.jpeg = self._jpeg()
        self.base_captured_at = datetime.now(UTC) - timedelta(minutes=5)

    def close(self) -> None:
        self.http.close()

    def run(self) -> None:
        self._wait_for_services()
        self._scenario_j_disallowed_class()
        self._scenario_a_b_frame_and_first_detection()
        self._scenario_c_persistent_detection()
        self._scenario_d_brief_miss()
        self._scenario_e_disappearance()
        self._scenario_i_manual_commands_and_f_duplicate()
        self._scenario_g_expired_command()
        self._scenario_h_publish_failure_and_reevaluation()
        self._verify_final_database_state()

    def _wait_for_services(self) -> None:
        self._wait_until(
            "Backend preflight readiness",
            lambda: self._preflight_ready(),
            timeout=60.0,
        )
        for name, base_url in (
            ("detected AI Mock", self.ai_detected_url),
            ("empty AI Mock", self.ai_empty_url),
        ):
            metadata = self._wait_until(
                f"{name} readiness",
                lambda url=base_url: self._ready_payload(url),
                timeout=30.0,
            )
            require(metadata["inference"] == "mock", f"{name} is not MockInference")
        online = self._wait_until(
            "simulator ONLINE status persistence",
            lambda: self._db_scalar(
                "SELECT operational_status FROM device_statuses "
                "WHERE device_id = 'pi-001' "
                "ORDER BY received_at DESC, id DESC LIMIT 1"
            )
            == "ONLINE",
        )
        require(online is True, "simulator ONLINE status was not persisted")
        print("E2E_SERVICES_READY=PASS", flush=True)

    def _scenario_j_disallowed_class(self) -> None:
        response = self._post_detection_event(
            captured_at=self._captured(0),
            class_code="CROW",
        )
        require(
            response["commandOutcome"] == "NOT_REQUESTED",
            f"disallowed class requested a command: {response}",
        )
        observation = self._observation()
        require(
            observation.presence_state == "IDLE",
            f"disallowed class started presence: {observation}",
        )
        require(
            observation.sound_alert_command_id is None
            and observation.deterrent_full_command_id is None,
            f"disallowed class recorded a command marker: {observation}",
        )
        print("SCENARIO_J_DISALLOWED_CLASS=PASS", flush=True)

    def _scenario_a_b_frame_and_first_detection(self) -> None:
        response = self._analyze(self.ai_detected_url, self._captured(1))
        command_id = self._created_command_id(response, "first detection")
        self._wait_command_status(command_id, "EXECUTED")
        command = self._command(command_id)
        self._require_command(
            command,
            source="AUTOMATIC",
            command_type="SOUND_ALERT",
            event_required=True,
        )

        event_id = response["eventId"]
        persisted = self._db_scalar(
            "SELECT count(*) FROM detection_events event "
            "JOIN animal_detections detection ON detection.event_id = event.id "
            f"WHERE event.event_id = {sql_text(event_id)} "
            "AND event.camera_id = 'cam-001' "
            "AND detection.class_code = 'MAGPIE'"
        )
        require(persisted == "1", "AI frame path did not persist MAGPIE detection")
        require(
            self._observation().presence_state == "PRESENT",
            "allowed AI detection did not start observation",
        )
        print("SCENARIO_A_FRAME_PATH=PASS", flush=True)
        print("SCENARIO_B_FIRST_DETECTION=PASS", flush=True)

    def _scenario_c_persistent_detection(self) -> None:
        self._wait_for_cooldown()
        response = self._analyze(self.ai_detected_url, self._captured(3))
        command_id = self._created_command_id(response, "persistent detection")
        self._wait_command_status(command_id, "EXECUTED")
        self._require_command(
            self._command(command_id),
            source="AUTOMATIC",
            command_type="DETERRENT_FULL",
            event_required=True,
        )
        print("SCENARIO_C_PERSISTENT_DETECTION=PASS", flush=True)

    def _scenario_d_brief_miss(self) -> None:
        stop_count = self._automatic_stop_count()
        empty = self._analyze(self.ai_empty_url, self._captured(4))
        require(
            empty["commandOutcome"] == "NOT_REQUESTED",
            f"brief miss requested a command: {empty}",
        )
        recovered = self._analyze(self.ai_detected_url, self._captured(4.5))
        require(
            recovered["commandOutcome"] == "NOT_REQUESTED",
            f"pre-grace recovery requested a command: {recovered}",
        )
        observation = self._observation()
        require(
            observation.presence_state == "PRESENT"
            and observation.absence_started_at is None,
            f"brief miss did not restore PRESENT state: {observation}",
        )
        require(
            self._automatic_stop_count() == stop_count,
            "brief miss created STOP_DETERRENT",
        )
        print("SCENARIO_D_BRIEF_MISS=PASS", flush=True)

    def _scenario_e_disappearance(self) -> None:
        first_empty = self._analyze(self.ai_empty_url, self._captured(6))
        require(
            first_empty["commandOutcome"] == "NOT_REQUESTED",
            f"first disappearance miss requested a command: {first_empty}",
        )
        response = self._analyze(self.ai_empty_url, self._captured(7.1))
        command_id = self._created_command_id(response, "confirmed disappearance")
        self._wait_command_status(command_id, "EXECUTED")
        self._require_command(
            self._command(command_id),
            source="AUTOMATIC",
            command_type="STOP_DETERRENT",
            event_required=True,
        )
        observation = self._observation()
        require(
            observation.presence_state == "IDLE"
            and observation.sound_alert_command_id is None
            and observation.deterrent_full_command_id is None,
            f"disappearance did not reset observation: {observation}",
        )
        print("SCENARIO_E_DISAPPEARANCE=PASS", flush=True)

    def _scenario_i_manual_commands_and_f_duplicate(self) -> None:
        left_id = self._manual_command("ROTATE_CAMERA_LEFT")
        self._wait_command_status(left_id, "EXECUTED")
        left = self._command(left_id)
        self._require_command(
            left,
            source="MANUAL",
            command_type="ROTATE_CAMERA_LEFT",
            event_required=False,
        )

        action_count = self._mock_gpio_count("ROTATE_CAMERA_LEFT")
        require(action_count == 1, f"unexpected left action count before duplicate: {action_count}")
        self._publish_duplicate(left)
        self._wait_until(
            "simulator duplicate log",
            lambda: f"중복 command 수신: commandId={left_id}" in self._simulator_output(),
        )
        require(
            self._mock_gpio_count("ROTATE_CAMERA_LEFT") == action_count,
            "duplicate commandId executed Mock GPIO again",
        )

        for command_type in ("ROTATE_CAMERA_RIGHT", "STOP_DETERRENT"):
            command_id = self._manual_command(command_type)
            self._wait_command_status(command_id, "EXECUTED")
            self._require_command(
                self._command(command_id),
                source="MANUAL",
                command_type=command_type,
                event_required=False,
            )
        print("SCENARIO_F_DUPLICATE=PASS", flush=True)
        print("SCENARIO_I_MANUAL_API=PASS", flush=True)

    def _scenario_g_expired_command(self) -> None:
        self._wait_for_cooldown()
        require(self._process_exists(self.simulator_pid), "simulator process is not running")
        sound_actions = self._mock_gpio_count("SOUND_ALERT")
        os.kill(self.simulator_pid, signal.SIGSTOP)
        resumed = False
        try:
            response = self._analyze(self.ai_detected_url, self._captured(10))
            command_id = self._created_command_id(response, "expiry setup")
            self._wait_command_status(command_id, "PUBLISHED")
            time.sleep(COMMAND_TTL_SECONDS + 0.4)
            os.kill(self.simulator_pid, signal.SIGCONT)
            resumed = True
            self._wait_command_status(command_id, "EXPIRED")
        finally:
            if not resumed and self._process_exists(self.simulator_pid):
                os.kill(self.simulator_pid, signal.SIGCONT)

        require(
            self._mock_gpio_count("SOUND_ALERT") == sound_actions,
            "expired command executed Mock GPIO",
        )
        self._wait_marker_cleared("sound_alert_command_id")
        require(
            self._observation().presence_state == "PRESENT",
            "expired marker reconciliation lost active observation",
        )

        retry = self._analyze(self.ai_detected_url, self._captured(10.5))
        retry_id = self._created_command_id(retry, "post-expiry reevaluation")
        self._wait_command_status(retry_id, "EXECUTED")
        self._require_command(
            self._command(retry_id),
            source="AUTOMATIC",
            command_type="SOUND_ALERT",
            event_required=True,
        )
        print("SCENARIO_G_EXPIRY_RECONCILIATION=PASS", flush=True)

    def _scenario_h_publish_failure_and_reevaluation(self) -> None:
        self._wait_for_cooldown()
        deterrent_actions = self._mock_gpio_count("DETERRENT_FULL")
        self.mqtt_drop_file.write_text("drop next Backend PUBLISH\n", encoding="utf-8")
        response = self._analyze(self.ai_detected_url, self._captured(12))
        command_id = self._created_command_id(response, "publish failure")
        self._wait_until(
            "fault proxy publish drop",
            lambda: not self.mqtt_drop_file.exists(),
        )
        self._wait_command_status(command_id, "FAILED")
        require(
            self._mock_gpio_count("DETERRENT_FULL") == deterrent_actions,
            "dropped publish reached Mock GPIO",
        )
        self._wait_marker_cleared("deterrent_full_command_id")
        self._wait_until(
            "Backend MQTT readiness after fault reconnect",
            self._preflight_ready,
            timeout=30.0,
        )

        self._wait_for_cooldown()
        retry = self._analyze(self.ai_detected_url, self._captured(13))
        retry_id = self._created_command_id(retry, "post-failure reevaluation")
        self._wait_command_status(retry_id, "EXECUTED")
        self._require_command(
            self._command(retry_id),
            source="AUTOMATIC",
            command_type="DETERRENT_FULL",
            event_required=True,
        )
        print("SCENARIO_H_PUBLISH_FAILURE_RECONCILIATION=PASS", flush=True)

    def _verify_final_database_state(self) -> None:
        flyway_version = self._db_scalar(
            "SELECT version FROM flyway_schema_history "
            "WHERE success = true ORDER BY installed_rank DESC LIMIT 1"
        )
        require(flyway_version == "5", f"unexpected Flyway version: {flyway_version}")
        bad_terminals = self._db_scalar(
            "SELECT count(*) FROM device_commands "
            "WHERE status IN ('CREATED', 'PUBLISHED', 'ACKNOWLEDGED')"
        )
        require(bad_terminals == "0", f"non-terminal commands remain: {bad_terminals}")
        print("FLYWAY_FINAL_VERSION=5", flush=True)

    def _preflight_ready(self) -> bool:
        try:
            response = self.http.get(f"{self.backend_url}/api/v1/actuation/preflight")
        except httpx.RequestError:
            return False
        if response.status_code != 200:
            return False
        payload = response.json()
        return payload == {"enabled": True, "ready": True, "blockers": []}

    def _ready_payload(self, base_url: str) -> dict[str, Any] | None:
        try:
            response = self.http.get(f"{base_url}/health/ready")
        except httpx.RequestError:
            return None
        if response.status_code != 200:
            return None
        payload = response.json()
        return payload if payload.get("status") == "READY" else None

    def _analyze(self, base_url: str, captured_at: datetime) -> dict[str, Any]:
        response = self.http.post(
            f"{base_url}/api/v1/analyze",
            files={"frame": ("frame.jpg", self.jpeg, "image/jpeg")},
            data={"cameraId": "cam-001", "capturedAt": rfc3339(captured_at)},
        )
        require(
            response.status_code == 200,
            f"AI analyze failed: status={response.status_code} body={response.text}",
        )
        payload = response.json()
        require(isinstance(payload, dict), "AI analyze response is not an object")
        return payload

    def _post_detection_event(
        self,
        *,
        captured_at: datetime,
        class_code: str,
    ) -> dict[str, Any]:
        event_id = str(uuid4())
        response = self.http.post(
            f"{self.backend_url}/api/v1/detection/events",
            json={
                "eventId": event_id,
                "cameraId": "cam-001",
                "capturedAt": rfc3339(captured_at),
                "image": {"width": 8, "height": 8},
                "model": {
                    "detectorVersion": "mock-e2e-direct-v1",
                    "classifierVersion": None,
                },
                "detections": [
                    {
                        "detectionId": f"det-{uuid4()}",
                        "trackId": None,
                        "classCode": class_code,
                        "detectionConfidence": 0.95,
                        "classificationConfidence": None,
                        "bbox": {"x": 0, "y": 0, "width": 4, "height": 4},
                    }
                ],
            },
        )
        require(
            response.status_code == 201,
            f"Backend detection event failed: status={response.status_code} body={response.text}",
        )
        return response.json()

    def _manual_command(self, command_type: str) -> str:
        request_id = str(uuid4())
        response = self.http.post(
            f"{self.backend_url}/api/v1/devices/pi-001/commands",
            headers={"X-Operator-Token": self.operator_token},
            json={"requestId": request_id, "command": command_type},
        )
        require(
            response.status_code == 201,
            f"manual command failed: type={command_type} "
            f"status={response.status_code} body={response.text}",
        )
        payload = response.json()
        require(
            payload.get("commandOutcome") == "CREATED",
            f"manual command was not CREATED: {payload}",
        )
        command_id = payload.get("commandId")
        require(command_id == f"manual-{request_id}", f"manual commandId mismatch: {payload}")
        return command_id

    def _publish_duplicate(self, command: CommandRow) -> None:
        payload = {
            "commandId": command.command_id,
            "eventId": command.event_id,
            "deviceId": command.device_id,
            "source": command.source,
            "command": command.command_type,
            "durationMs": command.duration_ms,
            "issuedAt": command.issued_at,
            "expiresAt": command.expires_at,
            "reason": command.reason,
        }
        self._compose(
            "exec",
            "-T",
            "mosquitto",
            "mosquitto_pub",
            "-h",
            "127.0.0.1",
            "-p",
            "1883",
            "-q",
            "1",
            "-t",
            "animalguard/devices/pi-001/commands",
            "-m",
            json.dumps(payload, separators=(",", ":")),
        )

    def _command(self, command_id: str) -> CommandRow:
        output = self._db(
            "SELECT command.command_id, COALESCE(event.event_id, ''), "
            "command.device_id, command.command_source, command.command_type, "
            "COALESCE(command.duration_ms::text, ''), command.reason, "
            "command.issued_at::text, command.expires_at::text, command.status "
            "FROM device_commands command "
            "LEFT JOIN detection_events event ON event.id = command.event_id "
            f"WHERE command.command_id = {sql_text(command_id)}"
        )
        require(bool(output), f"command row not found: {command_id}")
        fields = output.split("\t")
        require(len(fields) == 10, f"unexpected command row: {output}")
        return CommandRow(
            command_id=fields[0],
            event_id=fields[1] or None,
            device_id=fields[2],
            source=fields[3],
            command_type=fields[4],
            duration_ms=int(fields[5]) if fields[5] else None,
            reason=fields[6],
            issued_at=parse_database_timestamp(fields[7]),
            expires_at=parse_database_timestamp(fields[8]),
            status=fields[9],
        )

    def _observation(self) -> ObservationRow:
        output = self._db(
            "SELECT presence_state, COALESCE(sound_alert_command_id, ''), "
            "COALESCE(deterrent_full_command_id, ''), "
            "COALESCE(absence_started_at::text, '') "
            "FROM animal_observation_states WHERE camera_id = 'cam-001'"
        )
        require(bool(output), "cam-001 observation row not found")
        fields = output.split("\t")
        require(len(fields) == 4, f"unexpected observation row: {output}")
        return ObservationRow(
            presence_state=fields[0],
            sound_alert_command_id=fields[1] or None,
            deterrent_full_command_id=fields[2] or None,
            absence_started_at=fields[3] or None,
        )

    def _wait_command_status(self, command_id: str, expected_status: str) -> None:
        actual = self._wait_until(
            f"command {command_id} status {expected_status}",
            lambda: self._status_if_expected(command_id, expected_status),
        )
        require(actual == expected_status, f"command did not reach {expected_status}")

    def _status_if_expected(self, command_id: str, expected_status: str) -> str | None:
        status = self._db_scalar(
            "SELECT status FROM device_commands "
            f"WHERE command_id = {sql_text(command_id)}"
        )
        return status if status == expected_status else None

    def _wait_marker_cleared(self, column: str) -> None:
        allowed_columns = {"sound_alert_command_id", "deterrent_full_command_id"}
        require(column in allowed_columns, f"unsupported marker column: {column}")
        cleared = self._wait_until(
            f"observation marker {column} reconciliation",
            lambda: self._db_scalar(
                f"SELECT COALESCE({column}, '') FROM animal_observation_states "
                "WHERE camera_id = 'cam-001'"
            )
            == "",
        )
        require(cleared is True, f"marker was not cleared: {column}")

    @staticmethod
    def _require_command(
        command: CommandRow,
        *,
        source: str,
        command_type: str,
        event_required: bool,
    ) -> None:
        require(command.source == source, f"unexpected command source: {command}")
        require(command.command_type == command_type, f"unexpected command type: {command}")
        require(command.status == "EXECUTED", f"command is not EXECUTED: {command}")
        require(
            (command.event_id is not None) == event_required,
            f"unexpected command eventId: {command}",
        )
        if command_type in {"SOUND_ALERT", "DETERRENT_FULL"}:
            require(command.duration_ms == 100, f"unexpected duration: {command}")
        else:
            require(command.duration_ms is None, f"duration must be null: {command}")

    @staticmethod
    def _created_command_id(response: dict[str, Any], context: str) -> str:
        require(
            response.get("commandOutcome") == "CREATED",
            f"{context} did not create command: {response}",
        )
        command_id = response.get("commandId")
        require(isinstance(command_id, str) and command_id, f"{context} has no commandId")
        return command_id

    def _automatic_stop_count(self) -> int:
        return int(
            self._db_scalar(
                "SELECT count(*) FROM device_commands "
                "WHERE command_source = 'AUTOMATIC' "
                "AND command_type = 'STOP_DETERRENT'"
            )
        )

    def _mock_gpio_count(self, command_type: str) -> int:
        needle = f"Mock GPIO 실행: command={command_type} "
        return self._simulator_output().count(needle)

    def _simulator_output(self) -> str:
        try:
            return self.simulator_log.read_text(encoding="utf-8", errors="replace")
        except FileNotFoundError:
            return ""

    def _db_scalar(self, sql: str) -> str:
        output = self._db(sql)
        return output.splitlines()[0] if output else ""

    def _db(self, sql: str) -> str:
        result = self._compose(
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "animalguard",
            "-d",
            "animalguard",
            "--no-align",
            "--tuples-only",
            "--field-separator",
            "\t",
            "--set",
            "ON_ERROR_STOP=1",
            "--command",
            sql,
        )
        return result.stdout.rstrip("\r\n")

    def _compose(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [*self.compose_prefix, *arguments],
            check=True,
            capture_output=True,
            text=True,
        )

    @staticmethod
    def _wait_until(
        description: str,
        predicate: Callable[[], Any],
        *,
        timeout: float = DEFAULT_WAIT_SECONDS,
    ) -> Any:
        deadline = time.monotonic() + timeout
        last_error: Exception | None = None
        while time.monotonic() < deadline:
            try:
                result = predicate()
                if result:
                    return result
            except (httpx.RequestError, subprocess.CalledProcessError) as error:
                last_error = error
            time.sleep(0.1)
        detail = f"; last error={last_error}" if last_error is not None else ""
        raise E2EVerificationError(f"timed out waiting for {description}{detail}")

    @staticmethod
    def _process_exists(process_id: int) -> bool:
        try:
            os.kill(process_id, 0)
        except ProcessLookupError:
            return False
        return True

    def _captured(self, offset_seconds: float) -> datetime:
        return self.base_captured_at + timedelta(seconds=offset_seconds)

    @staticmethod
    def _wait_for_cooldown() -> None:
        time.sleep(COMMAND_COOLDOWN_SECONDS + 0.2)

    @staticmethod
    def _jpeg() -> bytes:
        output = BytesIO()
        with Image.new("RGB", (8, 8), color="white") as image:
            image.save(output, format="JPEG")
        return output.getvalue()


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--backend-url", required=True)
    parser.add_argument("--ai-detected-url", required=True)
    parser.add_argument("--ai-empty-url", required=True)
    parser.add_argument("--compose-file", type=Path, required=True)
    parser.add_argument("--compose-project", required=True)
    parser.add_argument("--operator-token", required=True)
    parser.add_argument("--simulator-pid", type=int, required=True)
    parser.add_argument("--simulator-log", type=Path, required=True)
    parser.add_argument("--mqtt-drop-file", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    verifier = MockE2E(arguments())
    try:
        verifier.run()
    finally:
        verifier.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
