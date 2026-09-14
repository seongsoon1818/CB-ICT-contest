# AnimalGuard Software Architecture v1

## 목적과 증거 경계

이 문서는 실제 model과 Raspberry Pi를 연결하기 직전의 software boundary를 정의합니다. Mock E2E는 동일한 HTTP·DB·MQTT 계약을 실제 Backend와 broker에서 실행하지만, model 품질이나 물리 장치 성공을 증명하지 않습니다.

## Component 책임

| Component | 책임 | 소유하지 않는 결정 |
| --- | --- | --- |
| camera-uploader | latest JPEG와 cameraId/capturedAt을 AI Server에 multipart 전송 | 추론, 위험 정책, command |
| AI Server | JPEG 검증·decode, inference adapter 실행, Detection Event v1 생성·전송 | risk score, response command, GPIO |
| Backend | event/risk 감사 저장, response eligibility, camera observation, command lifecycle, MQTT publish/ACK/status, reconciliation | model runtime, GPIO wiring |
| MQTT broker | QoS 1 message transport | policy, idempotency, 상태 판정 |
| MQTT Simulator | 실제 GPIO 없이 device parser·dedup·ACK/status 계약 검증 | 물리 장치 성공 |
| embedded controller | semantic command 검증·SQLite dedup·GPIO adapter 실행 | classCode별 정책, raw Backend GPIO 지시 |

## 정상 흐름

```text
JPEG + cameraId + capturedAt
  -> AI Server /api/v1/analyze
  -> Backend /api/v1/detection/events
  -> DetectionEvent + RiskDecision + camera observation
  -> DeviceCommand CREATED
  -> MQTT PUBLISHED
  -> Raspberry Pi ACKNOWLEDGED
  -> semantic GPIO action
  -> Raspberry Pi EXECUTED
  -> Backend EXECUTED
```

Response eligibility를 통과한 detection만 aggregate presence에 참여합니다. command vocabulary는 동물 종류가 아니라 `SOUND_ALERT`, `DETERRENT_FULL`, `STOP_DETERRENT`, 수동 `ROTATE_CAMERA_LEFT/RIGHT`로 제한됩니다.

## 실패와 안전 상태

- production 기본 설정은 MQTT, actuation, risk-policy confirmation, response policy와 operator API를 비활성화합니다.
- commandId는 Backend와 device의 durable idempotency key입니다. QoS 1 duplicate는 물리 동작을 반복하지 않습니다.
- Backend는 publish 전 `PUBLISHED`를 commit하고 즉시 transport 실패를 `FAILED`로 기록합니다.
- reconciliation은 만료 CREATED를 EXPIRED, stale PUBLISHED/ACKNOWLEDGED를 FAILED로 종결하고 FAILED/EXPIRED observation marker를 clear합니다. 자동 MQTT retry는 하지 않습니다.
- no-event watchdog은 full deterrent가 실행된 active session에 STOP을 요청하며, 실제 delivery나 GPIO stop을 추정하지 않습니다.
- STOP은 시작 정책과 cooldown 일부를 우회하는 safety command지만 mapping과 MQTT readiness는 요구합니다.

## Mock E2E topology

`scripts/e2e/animalguard_mock_e2e.sh`는 고유 Compose project에서 PostgreSQL과 Mosquitto를 만들고 host process로 Backend, detected/empty AI MockInference, MQTT Simulator를 기동합니다. test-only TCP fault proxy는 다음 Backend PUBLISH 한 번을 broker 전에 끊어 실제 Paho failure handling을 결정적으로 실행합니다.

검증 시나리오는 frame path, first/persistent detection, brief miss, disappearance, duplicate, expiry, publish failure, manual API와 allowed/disallowed response eligibility입니다. 검증기는 public API, 격리 PostgreSQL, Simulator log/SQLite만 읽고 production 편의 endpoint를 추가하지 않습니다.

## 실제 연결 전 경계

Model 연결에는 bundle, runtime, output adapter, classes.json과 confidence/risk/response 값이 필요합니다. Raspberry Pi 연결에는 broker credential, device/camera mapping, GPIO pin, camera/systemd 설정과 실제 command/ACK/30FPS evidence가 필요합니다. 활성화 순서는 [`SOFTWARE_READY_CHECKLIST.md`](SOFTWARE_READY_CHECKLIST.md)를 따릅니다.
