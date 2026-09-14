#!/usr/bin/env bash
set -Eeuo pipefail

REPOSITORY_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_FILE="$REPOSITORY_ROOT/infra/docker-compose.yml"
COMPOSE_PROJECT="animalguard-mock-e2e-${PPID}-$$"
E2E_TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/animalguard-mock-e2e.XXXXXX")"

POSTGRES_HOST_PORT="${POSTGRES_HOST_PORT:-55432}"
MQTT_HOST_PORT="${MQTT_HOST_PORT:-51883}"
MQTT_BACKEND_PROXY_PORT="${MQTT_BACKEND_PROXY_PORT:-51884}"
BACKEND_PORT="${BACKEND_PORT:-18080}"
AI_DETECTED_PORT="${AI_DETECTED_PORT:-18000}"
AI_EMPTY_PORT="${AI_EMPTY_PORT:-18001}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-mock-e2e-postgres-only}"
OPERATOR_API_TOKEN="${OPERATOR_API_TOKEN:-mock-e2e-operator-token}"

AI_PYTHON="${AI_PYTHON:-$REPOSITORY_ROOT/ai-server/.venv/bin/python}"
MQTT_SIMULATOR_PYTHON="${MQTT_SIMULATOR_PYTHON:-$REPOSITORY_ROOT/raspberry-pi/mqtt-simulator/.venv/bin/python}"
if [[ ! -x "$AI_PYTHON" ]]; then
  AI_PYTHON="python3"
fi
if [[ ! -x "$MQTT_SIMULATOR_PYTHON" ]]; then
  MQTT_SIMULATOR_PYTHON="python3"
fi

BACKEND_LOG="$E2E_TMP_DIR/backend.log"
AI_DETECTED_LOG="$E2E_TMP_DIR/ai-detected.log"
AI_EMPTY_LOG="$E2E_TMP_DIR/ai-empty.log"
MQTT_SIMULATOR_LOG="$E2E_TMP_DIR/mqtt-simulator.log"
MQTT_PROXY_LOG="$E2E_TMP_DIR/mqtt-proxy.log"
MQTT_SIMULATOR_DB="$E2E_TMP_DIR/processed_commands.db"
MQTT_DROP_FILE="$E2E_TMP_DIR/drop-next-publish"

BACKEND_PID=""
AI_DETECTED_PID=""
AI_EMPTY_PID=""
MQTT_SIMULATOR_PID=""
MQTT_PROXY_PID=""

compose() {
  docker compose --project-name "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" "$@"
}

stop_process() {
  local process_id="$1"
  if [[ -n "$process_id" ]] && kill -0 "$process_id" 2>/dev/null; then
    kill -CONT "$process_id" 2>/dev/null || true
    kill -TERM "$process_id" 2>/dev/null || true
    wait "$process_id" 2>/dev/null || true
  fi
}

cleanup() {
  local exit_status=$?
  trap - EXIT INT TERM
  stop_process "$AI_DETECTED_PID"
  stop_process "$AI_EMPTY_PID"
  stop_process "$MQTT_SIMULATOR_PID"
  stop_process "$BACKEND_PID"
  stop_process "$MQTT_PROXY_PID"
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  if (( exit_status != 0 )); then
    for log_file in \
      "$BACKEND_LOG" \
      "$AI_DETECTED_LOG" \
      "$AI_EMPTY_LOG" \
      "$MQTT_SIMULATOR_LOG" \
      "$MQTT_PROXY_LOG"; do
      if [[ -f "$log_file" ]]; then
        echo "===== ${log_file##*/} (tail) =====" >&2
        tail -80 "$log_file" >&2 || true
      fi
    done
  fi
  if [[ -n "$E2E_TMP_DIR" && -d "$E2E_TMP_DIR" ]]; then
    rm -rf -- "$E2E_TMP_DIR"
  fi
  exit "$exit_status"
}

trap cleanup EXIT
trap 'exit 130' INT TERM

command -v docker >/dev/null
command -v java >/dev/null
[[ -x "$REPOSITORY_ROOT/backend-server/gradlew" ]]
"$AI_PYTHON" -c 'import httpx, PIL, uvicorn'
"$MQTT_SIMULATOR_PYTHON" -c 'import paho.mqtt.client'

export POSTGRES_HOST_PORT MQTT_HOST_PORT POSTGRES_PASSWORD
compose config --quiet
compose up -d --wait postgres mosquitto

(
  cd "$REPOSITORY_ROOT/backend-server"
  ./gradlew bootJar --no-daemon --no-watch-fs
)

BACKEND_JAR="$REPOSITORY_ROOT/backend-server/build/libs/animalguard-backend-0.0.1-SNAPSHOT.jar"
[[ -f "$BACKEND_JAR" ]]

(
  exec "$AI_PYTHON" "$REPOSITORY_ROOT/scripts/e2e/mqtt_fault_proxy.py" \
    --listen-port "$MQTT_BACKEND_PROXY_PORT" \
    --upstream-port "$MQTT_HOST_PORT" \
    --drop-next-publish-file "$MQTT_DROP_FILE"
) >"$MQTT_PROXY_LOG" 2>&1 &
MQTT_PROXY_PID=$!

(
  cd "$REPOSITORY_ROOT/raspberry-pi/mqtt-simulator"
  exec env \
    MQTT_HOST=127.0.0.1 \
    MQTT_PORT="$MQTT_HOST_PORT" \
    MQTT_DEVICE_ID=pi-001 \
    MQTT_KEEPALIVE_SECONDS=30 \
    STATUS_INTERVAL_SECONDS=1 \
    PROCESSED_COMMAND_DB="$MQTT_SIMULATOR_DB" \
    "$MQTT_SIMULATOR_PYTHON" -m animalguard_mqtt.main
) >"$MQTT_SIMULATOR_LOG" 2>&1 &
MQTT_SIMULATOR_PID=$!

# This Mock E2E exercises the full observation lifecycle independently of the production HIGH-only gate.
(
  exec env \
    SPRING_PROFILES_ACTIVE=local \
    DB_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_HOST_PORT/animalguard" \
    DB_USERNAME=animalguard \
    DB_PASSWORD="$POSTGRES_PASSWORD" \
    SERVER_PORT="$BACKEND_PORT" \
    MQTT_ENABLED=true \
    MQTT_HOST=127.0.0.1 \
    MQTT_PORT="$MQTT_BACKEND_PROXY_PORT" \
    MQTT_CLIENT_ID=animalguard-backend-mock-e2e \
    MQTT_CONNECT_TIMEOUT=2s \
    MQTT_PUBLISH_TIMEOUT=500ms \
    MQTT_DISPATCH_INTERVAL=100ms \
    MQTT_DISPATCH_BATCH_SIZE=20 \
    ACTUATION_ENABLED=true \
    RISK_POLICY_CONFIRMED=true \
    RESPONSE_POLICY_ENABLED=true \
    RESPONSE_ALLOWED_CLASS_CODES=MAGPIE \
    RESPONSE_MIN_RISK_LEVEL=LOW \
    OPERATOR_API_ENABLED=true \
    OPERATOR_API_TOKEN="$OPERATOR_API_TOKEN" \
    ANIMAL_PERSISTENCE_THRESHOLD=1s \
    ANIMAL_ABSENCE_GRACE=1s \
    ANIMAL_CONTINUITY_TIMEOUT=3s \
    ANIMAL_NO_EVENT_TIMEOUT=30s \
    SOUND_ALERT_DURATION=100ms \
    DETERRENT_FULL_DURATION=100ms \
    DEVICE_COMMAND_COOLDOWN=1s \
    DEVICE_COMMAND_TTL=1s \
    COMMAND_RECONCILIATION_INTERVAL=100ms \
    COMMAND_PUBLISHED_TIMEOUT=10s \
    COMMAND_ACKNOWLEDGED_TIMEOUT=10s \
    MAX_AUTOMATIC_ATTEMPTS_PER_SESSION=3 \
    java -jar "$BACKEND_JAR"
) >"$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!

(
  cd "$REPOSITORY_ROOT/ai-server"
  exec env \
    BACKEND_BASE_URL="http://127.0.0.1:$BACKEND_PORT" \
    INFERENCE_MODE=mock \
    MOCK_RESULT=detected \
    "$AI_PYTHON" -m uvicorn app.main:app \
      --host 127.0.0.1 --port "$AI_DETECTED_PORT" --log-level warning
) >"$AI_DETECTED_LOG" 2>&1 &
AI_DETECTED_PID=$!

(
  cd "$REPOSITORY_ROOT/ai-server"
  exec env \
    BACKEND_BASE_URL="http://127.0.0.1:$BACKEND_PORT" \
    INFERENCE_MODE=mock \
    MOCK_RESULT=empty \
    "$AI_PYTHON" -m uvicorn app.main:app \
      --host 127.0.0.1 --port "$AI_EMPTY_PORT" --log-level warning
) >"$AI_EMPTY_LOG" 2>&1 &
AI_EMPTY_PID=$!

"$AI_PYTHON" "$REPOSITORY_ROOT/scripts/e2e/verify_mock_e2e.py" \
  --backend-url "http://127.0.0.1:$BACKEND_PORT" \
  --ai-detected-url "http://127.0.0.1:$AI_DETECTED_PORT" \
  --ai-empty-url "http://127.0.0.1:$AI_EMPTY_PORT" \
  --compose-file "$COMPOSE_FILE" \
  --compose-project "$COMPOSE_PROJECT" \
  --operator-token "$OPERATOR_API_TOKEN" \
  --simulator-pid "$MQTT_SIMULATOR_PID" \
  --simulator-log "$MQTT_SIMULATOR_LOG" \
  --mqtt-drop-file "$MQTT_DROP_FILE"

echo "MOCK_E2E=PASS"
