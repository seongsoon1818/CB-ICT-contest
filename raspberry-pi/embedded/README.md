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

실행 전에 Pi 시계와 NTP 동기화를 확인합니다. 장치 시계가 틀리면 아직 유효한 command도
`EXPIRED`로 처리할 수 있습니다.

```bash
timedatectl status
timedatectl show -p NTPSynchronized --value
```

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

tracked `.env.example`은 연결 식별자 예시로 `MQTT_DEVICE_ID=pi-001`을 사용하지만,
broker 주소·인증 정보, 다섯 BCM pin과 두 maximum duration은 의도적으로 비워 둡니다.
실제 배선표와 모듈 사양을 확인하지 않은 값을 넣어 기동해서는 안 됩니다. 예시의
`PROCESSED_COMMAND_DB=/var/lib/animalguard/processed_commands.db`는 아래 systemd unit의
`StateDirectory=animalguard`와 함께 사용합니다. 수동 실행에서는 해당 부모 디렉터리를
실행 사용자가 쓸 수 있도록 먼저 준비해야 합니다.

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

## systemd 설치 예시

`systemd/animalguard-embedded.service`는 다음 경로와 계정을 가정합니다.

- 애플리케이션: `/opt/animalguard/embedded`
- 환경 파일: `/etc/animalguard/embedded.env`
- 서비스 사용자: `animalguard`
- GPIO supplementary group: `gpio`
- SQLite state 디렉터리: `/var/lib/animalguard`

애플리케이션과 venv를 `/opt/animalguard/embedded`에 배치한 뒤, 실제 설정 파일은
root만 읽도록 설치합니다. unit의 `StateDirectory`가 서비스 시작 시 SQLite 부모
디렉터리를 만들고 `animalguard` 사용자에게 쓰기 권한을 부여합니다.

```bash
id -u animalguard >/dev/null 2>&1 || \
  sudo useradd --system --user-group --home-dir /var/lib/animalguard \
    --shell /usr/sbin/nologin animalguard
getent group gpio

cd /opt/animalguard/embedded
sudo python3 -m venv .venv
sudo .venv/bin/python -m pip install -r requirements.txt

sudo install -d -o root -g root -m 0755 /etc/animalguard
sudo install -o root -g root -m 0600 \
  .env.example /etc/animalguard/embedded.env
sudoedit /etc/animalguard/embedded.env

sudo cp systemd/animalguard-embedded.service /etc/systemd/system/
sudo systemd-analyze verify \
  /etc/systemd/system/animalguard-embedded.service
sudo systemctl daemon-reload
sudo systemctl enable --now animalguard-embedded.service
sudo systemctl status animalguard-embedded.service
```

이미 `animalguard` 사용자가 있으면 `useradd`를 다시 실행하지 않습니다. Raspberry Pi
OS에 `gpio` group이 없거나 실제 GPIO backend가 다른 권한을 요구하면 unit을 enable하기
전에 장치 기준으로 권한을 확정합니다. 환경 파일에는 tracked 예시가 아니라 실제
broker credential이 들어가므로 저장소에 복사하거나 로그에 출력하지 않습니다.

unit은 실패 시 5초 뒤 재시작하되 60초 동안 3회로 제한하고, 종료 시 `SIGTERM`을 보내
진행 중인 deterrent를 정지시킨 뒤 OFFLINE status 발행을 시도합니다. 설치·enable,
실제 GPIO 권한, broker 연결과 LWT/ACK 동작은 저장소 테스트가 수행하지 않습니다.
