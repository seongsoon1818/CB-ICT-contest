# AnimalGuard Raspberry Pi Embedded Controller

실제 Raspberry Pi에서 AnimalGuard MQTT v1 semantic command를 GPIO 동작으로 변환하는 controller입니다. Backend 계약의 source of truth는 `docs/contracts/mqtt-v1.md`이며 이 component는 GPIO 번호나 raw PWM을 MQTT로 받지 않습니다.

## 설치와 실행

Python 3.11 이상을 사용합니다. Raspberry Pi runtime은 gpiozero가 포함된 production requirements를 설치합니다.

```bash
cd raspberry-pi/embedded
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
cp .env.example .env
```

`.env`는 자동으로 읽지 않습니다. 실제 배포 환경이나 shell에서 값을 export한 뒤 실행합니다.

```bash
set -a
source .env
set +a
.venv/bin/python -m animalguard_embedded.main
```

기존 진입점이 필요한 배포는 `python mqtt_motor_controller.py`를 사용할 수 있으며 같은 package main을 호출합니다.

## 필수 설정

broker host, device ID, SQLite 경로, 실제 BCM pin과 최대 duration은 기본값 없이 요구합니다. 팀이 실제 배선·모듈을 확인하기 전 pin을 운영 기본값으로 확정하지 않습니다. username/password는 선택 사항이며 password만 단독으로 설정하면 기동을 거절합니다.

- `MQTT_HOST`, `MQTT_PORT`, `MQTT_USERNAME`, `MQTT_PASSWORD`
- `MQTT_DEVICE_ID`, `MQTT_KEEPALIVE_SECONDS`
- `PROCESSED_COMMAND_DB`
- `MOTOR_IN1_PIN`, `MOTOR_IN2_PIN`, `MOTOR_SLEEP_PIN`
- `SERVO_PIN`, `SPEAKER_PIN`
- `SERVO_STEP_DEGREES`, `SERVO_MIN_ANGLE`, `SERVO_MAX_ANGLE`
- `MAX_MOTOR_DURATION_MS`, `MAX_SOUND_DURATION_MS`

모든 GPIO pin은 서로 달라야 합니다. 기본 회전 step 예시는 5도이며 left는 현재 angle을 감소시키고 right는 증가시킵니다. min/max에서 clamp하고 프로세스 안에서 현재 angle을 유지합니다.

## MQTT와 ACK

device ID가 `pi-001`이면 controller는 다음 topic을 사용합니다.

- subscribe: `animalguard/devices/pi-001/commands`
- ACK: `animalguard/devices/pi-001/acks`
- status/LWT: `animalguard/devices/pi-001/status`

command와 ACK는 QoS 1, retain=false입니다. command topic, QoS 또는 retain envelope가 다르면 물리 실행하지 않습니다. status는 현재 상태 발견을 위해 retain=true로 발행하며 연결 시 ONLINE, 정상 종료와 Last Will은 OFFLINE입니다. `DEGRADED`와 `MAINTENANCE`도 동일한 4-field status 계약으로 생성할 수 있습니다. firmwareVersion은 `animalguard-embedded-v1` 상수입니다.

정상 command는 matching `acknowledgedAt`만 가진 ACKNOWLEDGED를 SQLite에 먼저 저장·발행한 뒤 실행하고, 성공 시 matching `executedAt`만 가진 EXECUTED로 갱신합니다. 만료는 EXPIRED, parser·local safety limit·GPIO 오류는 FAILED입니다. ACK에 계약 밖 `error` field를 추가하지 않고 상세 원인은 local log에만 남깁니다.

## GPIO 의미

- `ROTATE_CAMERA_LEFT` / `ROTATE_CAMERA_RIGHT`: servo를 설정 step만큼 이동하고 min/max clamp
- `SOUND_ALERT`: speaker만 켜고 duration 뒤 끔
- `DETERRENT_FULL`: motor와 speaker를 켜고 duration 뒤 모두 끔
- `STOP_DETERRENT`: 진행 중 timed worker에 stop signal을 보내고 motor/speaker를 즉시 끔; servo angle은 변경하지 않음

speaker 출력은 active buzzer 또는 digital relay의 on/off 방식입니다. PWM 음원이나 다른 speaker module이 필요하면 MQTT/handler가 아니라 `GPIOAdapter` 구현만 교체합니다.

## Persistent dedup

표준 `sqlite3`의 `processed_commands` table에 `command_id` primary key, device ID, status, ACK JSON과 processed time을 저장합니다. 같은 commandId를 다시 받거나 프로세스를 재시작해도 GPIO를 재실행하지 않고 저장된 마지막 ACK만 재발행합니다. ACKNOWLEDGED 저장 직후 crash가 발생한 경우에도 안전을 위해 재실행하지 않습니다.

## 테스트와 증거 경계

일반 개발 PC에서는 gpiozero를 import하지 않고도 package와 테스트를 실행할 수 있습니다.

```bash
python3 -m venv .venv-test
.venv-test/bin/python -m pip install -r requirements-dev.txt
.venv-test/bin/python -m pytest
```

테스트는 FakeGPIO와 fake MQTT client만 사용합니다. 실제 Raspberry Pi, GPIO wiring, motor/servo/speaker module과 broker delivery 성공을 증명하지 않습니다.

`HARDWARE_MQTT_SMOKE=NOT_RUN`
