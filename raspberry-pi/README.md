# Raspberry Pi

이 디렉터리는 AnimalGuard Raspberry Pi 장치 구성요소의 경계입니다. 장치는 MQTT의 의미 기반 command를 GPIO 동작으로 매핑하고 명령 처리 결과와 장치 상태를 발행합니다.

`embedded/`에는 MQTT v1 계약에 맞춘 실제 controller 소스가 있으며, 설정·parser·SQLite dedup·GPIO adapter·MQTT lifecycle을 분리합니다. 로컬 테스트는 FakeGPIO로 검증했지만 실제 Raspberry Pi 배선과 module 동작은 별도 hardware smoke가 필요합니다. 설치·설정·증거 경계는 [`embedded/README.md`](embedded/README.md)를 따릅니다.

- `camera-uploader/`: latest-frame source를 `frame`, `cameraId`, `capturedAt` multipart로 AI Server `/api/v1/analyze`에 전송
- `mqtt-simulator/`: 실제 GPIO 없이 command parser, persistent dedup, ACK/status를 검증
- `embedded/`: 실제 Pi용 gpiozero adapter와 최종 MQTT v1 controller

구 `test_frame_receiver.py`는 어떤 문서·script·component에서도 참조되지 않고 `/api/v1/frames`라는 폐기된 계약을 제공해 제거했습니다. local frame smoke도 production과 같은 `/api/v1/analyze` 계약을 사용해야 하며 receiver 응답을 AI 분석 성공으로 간주하지 않습니다.

전체 Mock E2E는 Simulator를 사용하고 실제 embedded controller를 기동하지 않습니다. 따라서 E2E PASS와 별개로 `HARDWARE_MQTT_SMOKE=NOT_RUN`일 수 있으며, 실제 device 연결 전에는 `docs/SOFTWARE_READY_CHECKLIST.md`의 broker·mapping·GPIO·camera·systemd·30FPS 항목을 확인해야 합니다.

## Raspberry Pi 연결 ID

다음 세 값은 한 묶음으로 관리합니다. tracked 예시는 한 대의 Pi 연결을 위해 아래
식별자를 사용합니다.

| 위치 | 설정 | 예시 값 |
| --- | --- | --- |
| camera-uploader | `CAMERA_ID` | `cam-001` |
| Backend | `camera-device-mappings` | `cam-001: pi-001` |
| embedded controller | `MQTT_DEVICE_ID` | `pi-001` |

Backend 운영 기본 설정의 mapping은 안전을 위해 빈 map입니다. 다음 내용을 예를 들어
`/etc/animalguard/backend-device-mapping.yml`에 두고, Backend가 외부 설정으로 읽게
합니다.

```yaml
animalguard:
  device-control:
    camera-device-mappings:
      cam-001: pi-001
```

```bash
export SPRING_CONFIG_ADDITIONAL_LOCATION=\
file:/etc/animalguard/backend-device-mapping.yml
```

`application-local.yml`에도 같은 mapping이 있지만 local profile 예시일 뿐 운영 장치
inventory를 대신하지 않습니다. 실제 camera/device ID가 바뀌면 세 위치를 함께 바꾸고
preflight에서 `CAMERA_DEVICE_MAPPING_EMPTY`와 `CAMERA_UNMAPPED`가 사라졌는지
확인합니다.

## Broker와 Backend 연결

저장소 루트 `.env.example`은 Backend MQTT transport와 안전 gate 이름을 보여 줍니다.
실제 secret은 tracked 파일에 넣지 않습니다. broker 연결을 검증할 때는 다음 값을
배포 환경에 제공하되 actuation과 policy gate는 계속 `false`로 둡니다.

```text
MQTT_ENABLED=true
MQTT_HOST=<broker-host>
MQTT_PORT=1883
MQTT_CLIENT_ID=animalguard-backend
MQTT_USERNAME=<backend-username>
MQTT_PASSWORD=<secret>

ACTUATION_ENABLED=false
RISK_POLICY_CONFIRMED=false
RESPONSE_POLICY_ENABLED=false
OPERATOR_API_ENABLED=false
```

Backend는 연결 후 다음 topic을 QoS 1로 구독합니다.

```text
animalguard/devices/+/acks
animalguard/devices/+/status
```

현재 Backend는 `tcp://` broker URI만 만들고 embedded controller도 `tls_set()`을
호출하지 않습니다. 따라서 TLS 전용 broker에는 연결할 수 없습니다. 그런 broker를
사용해야 하면 Backend와 Pi 양쪽 TLS 설정·인증서 검증을 먼저 구현하고 검증합니다.
포트만 `8883`으로 바꾸는 것은 TLS 지원이 아닙니다.

## 배포 설정 예시

- [`camera-uploader/.env.example`](camera-uploader/.env.example): AI Server 주소만 실제
  값으로 채우며 `CAMERA_ID=cam-001`, 목표 `CAPTURE_FPS=30`을 사용합니다.
- [`embedded/.env.example`](embedded/.env.example): broker·credential·실제 BCM pin과
  module-safe duration을 채우며 `MQTT_DEVICE_ID=pi-001`을 사용합니다.
- [`camera-uploader/systemd/animalguard-camera-uploader.service`](camera-uploader/systemd/animalguard-camera-uploader.service):
  `/etc/animalguard/camera-uploader.env`를 읽습니다.
- [`embedded/systemd/animalguard-embedded.service`](embedded/systemd/animalguard-embedded.service):
  `/etc/animalguard/embedded.env`를 읽고 SQLite용 `/var/lib/animalguard`를 만듭니다.

두 Python 프로그램은 `.env`를 자동으로 읽지 않습니다. 수동 smoke에서는 `set -a`와
`source`로 export하고, 무인 실행에서는 각 systemd `EnvironmentFile`을 사용합니다.
embedded controller를 시작하기 전에 `timedatectl`로 NTP 동기화도 확인합니다. 잘못된
Pi 시계는 유효한 command를 만료로 판단하게 할 수 있습니다.

## 안전한 실제 장치 검증 순서

1. Backend의 `ACTUATION_ENABLED`, `RISK_POLICY_CONFIRMED`,
   `RESPONSE_POLICY_ENABLED`, `OPERATOR_API_ENABLED`를 모두 `false`로 유지합니다.
2. broker와 `MQTT_ENABLED=true`인 Backend를 기동하고 ACK/status SUBACK를 확인합니다.
3. embedded controller를 기동해 retained `ONLINE` status와
   `animalguard/devices/pi-001/commands` QoS 1 구독을 확인합니다.
4. Backend preflight에서 `MQTT_PUBLISHER_NOT_READY`, mapping 관련 blocker가
   사라졌는지 확인합니다. 다른 안전 blocker가 남는 것은 이 단계에서 정상입니다.
5. 실제 model JPEG smoke와 camera-uploader의 `captured`, `uploaded`, `overwritten`,
   `effectiveCaptureFps`, `effectiveUploadFps`, `latestFrameAgeMs`를 측정합니다.
6. 실제 model/class/threshold와 장치 response policy를 승인합니다.
7. `RISK_POLICY_CONFIRMED`, `RESPONSE_POLICY_ENABLED`, 마지막으로
   `ACTUATION_ENABLED`를 활성화하고 preflight를 다시 확인합니다.
8. 수동 회전/STOP이 필요할 때만 `OPERATOR_API_ENABLED=true`와 별도 secret token을
   배포합니다.

```bash
curl -fsS http://<backend-host>:8080/api/v1/actuation/preflight
```

최종 actuation 시작 전 기대값은 다음과 같습니다.

```json
{
  "enabled": true,
  "ready": true,
  "blockers": []
}
```

수동 API는 `ROTATE_CAMERA_LEFT`, `ROTATE_CAMERA_RIGHT`, `STOP_DETERRENT`만
허용합니다. `SOUND_ALERT`와 `DETERRENT_FULL`은 수동 REST API로 보낼 수 없으므로,
승인된 automatic detection 흐름 또는 격리된 broker smoke에서 검증합니다. 모든 실제
hardware smoke는 좌/우 servo, speaker, motor+speaker, 즉시 STOP, duplicate/expiry
무실행, `ACKNOWLEDGED` 뒤 terminal ACK, 비정상 종료 LWT와 camera 30 FPS/backpressure
증거를 각각 남겨야 합니다.

이 저장소의 source test는 unit 파일 내용과 fake MQTT/GPIO 경계를 확인할 뿐입니다.
실제 Pi 설치 전까지 `HARDWARE_SMOKE=NOT_RUN`, `HARDWARE_MQTT_SMOKE=NOT_RUN`,
`HARDWARE_30FPS_SMOKE=NOT_RUN`입니다.

## 운영 관리 포트

- SSH 22/TCP: 기본 원격 터미널·배포·로그 관리 수단
- VNC 5090/TCP: 필요한 경우에만 사용하는 선택적 GUI 관리 수단

SSH와 VNC는 운영 관리용이며 카메라 프레임 또는 MQTT command 전송 경로가 아닙니다. 이번 작업에서는 포트를 열거나 방화벽과 VNC Server를 설정하지 않습니다. VNC 5090의 실제 listen 또는 port mapping 방식은 Raspberry Pi 구성 단계에서 확정합니다.
