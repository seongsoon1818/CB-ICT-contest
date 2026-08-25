# AnimalGuard Raspberry Pi MQTT Simulator

실제 GPIO 없이 AnimalGuard MQTT v1 command, expiry, commandId 중복 방지와 ACK 흐름을 검증하는 로컬 simulator입니다. 계약의 source of truth는 `docs/contracts/mqtt-v1.md`입니다.

## 실행

저장소 루트에서 로컬 전용 broker를 시작합니다.

```bash
docker compose -f infra/docker-compose.yml up -d mosquitto
```

Simulator 디렉터리에서 Python 3.11 이상 환경을 준비하고 실행합니다.

```bash
cd raspberry-pi/mqtt-simulator
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
cp .env.example .env
set -a
source .env
set +a
.venv/bin/python -m animalguard_mqtt.main
```

`.env` 파일을 자동으로 읽는 추가 의존성은 사용하지 않습니다. 위처럼 환경 변수로 내보내거나 실행 환경에서 직접 설정해야 합니다.

## MQTT 동작

Device ID가 `pi-001`이면 다음 topic을 사용합니다.

- command subscribe: `animalguard/devices/pi-001/commands`
- ACK publish: `animalguard/devices/pi-001/acks`
- status publish: `animalguard/devices/pi-001/status`

command와 ACK는 QoS 1이며 retain하지 않습니다. QoS 0이거나 retained delivery인 command는 실행하지 않고, 식별자가 유효하면 FAILED로 저장·발행합니다. Simulator는 command topic에 payload의 `deviceId`와 같은 설정값을 사용합니다. Device ID에 topic 구분자나 공백이 있으면 하나의 topic segment가 되도록 percent-encoding합니다.

연결 성공 시 ONLINE을 발행하고 설정 주기마다 heartbeat를 보냅니다. 정상 종료 시 OFFLINE을 발행합니다. 로컬 개발 편의를 위해 status만 retain합니다. 이 선택은 운영 retain 정책을 확정하는 것이 아닙니다.

## Command 검증과 중복 방지

계약에 정의된 8개 필드를 모두 요구하고 알 수 없는 추가 필드는 거절합니다. 따라서 GPIO 번호, raw PWM과 그 밖의 계약 외 필드도 거절됩니다. UUID, deviceId, 양수 duration, timezone 포함 시각, 발행·만료 순서와 semantic command allowlist를 검증합니다.

정상 command는 ACKNOWLEDGED 발행 후 Mock GPIO를 즉시 기록하고 EXECUTED를 발행합니다. 실제 `durationMs`만큼 sleep하지 않습니다. 만료 command는 EXPIRED, 지원하지 않거나 검증·실행에 실패한 command는 가능한 식별자가 있을 때 FAILED로 저장·발행합니다. 식별자를 파싱할 수 없는 JSON에는 ACK를 발행하지 않습니다.

SQLite의 `processed_commands` 테이블에 commandId와 마지막 ACK를 저장합니다. 중복 commandId는 Mock GPIO를 재실행하지 않고 저장된 ACK만 다시 발행합니다. ACKNOWLEDGED 저장 직후 프로세스가 중단된 경우에도 안전을 위해 재실행하지 않고 저장된 ACK를 재발행합니다.

## Mock GPIO 매핑

- `DETERRENT_LEVEL_1`: LED mock log
- `DETERRENT_LEVEL_2`: MOTOR/SPEAKER/LED mock log
- `DETERRENT_LEVEL_3`: 강화 동작 mock log
- `STOP_DETERRENT`: 모든 장치 OFF mock log

실제 pin 번호나 GPIO 라이브러리는 사용하지 않습니다.

## 테스트

```bash
.venv/bin/python -m pytest
```

Broker smoke test는 Mosquitto와 simulator를 실행한 뒤 command topic에 QoS 1, retain=false인 command를 발행하고 ACK topic에서 ACKNOWLEDGED와 EXECUTED를 확인합니다. 같은 payload를 다시 발행하면 저장된 EXECUTED만 수신되고 Mock GPIO 실행 로그가 추가되지 않아야 합니다.

## 비운영 범위

이 simulator와 익명 Mosquitto는 로컬 검증 전용입니다. TLS, username/password, ACL, 실제 GPIO, 실제 Raspberry Pi 배포, Backend Publisher, sensor-event와 AI 모델을 구현하지 않습니다.
