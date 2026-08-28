# AnimalGuard Software Ready Checklist

## 확인된 software 범위

- 실제 Backend와 Flyway v1→v5, PostgreSQL, Mosquitto를 사용합니다.
- 저장소 Mock E2E의 AI Server는 `MockInference(detected|empty)`를 사용합니다. 실제 모델 adapter 검증은 별도 범위입니다.
- Raspberry Pi 역할은 MQTT Simulator가 수행하며 실제 GPIO는 사용하지 않습니다.
- `scripts/e2e/animalguard_mock_e2e.sh`는 격리 Compose project·임시 volume/process를 만들고 종료 시 정리합니다.
- frame, first/persistent detection, brief miss, disappearance, duplicate, expiry, publish failure reconciliation, manual API와 response eligibility가 모두 PASS해야 software-ready로 판정합니다.

## 모델 연결 시

- [x] versioned model bundle metadata와 manifest 경로를 `models/bundle-metadata/wildlife-yolov8n-11class/`로 확정합니다. 실제 `.pt`는 배포 bundle에만 둡니다.
- [x] runtime을 `ultralytics-8.4.125`로 확정하고 dependency version을 고정합니다.
- [x] `ultralytics-yolo-detect-v1` output adapter가 Detection Event v1 `detections[]`로 변환하는 단위 테스트를 통과합니다.
- [x] 실제 checkpoint 내 ID 0~10과 `classes.json`의 11개 classCode 순서를 대조합니다.
- [ ] `RESPONSE_ALLOWED_CLASS_CODES`가 실제 `classes.json`의 운영 대상과 일치하는지 확인합니다.
- [ ] `RESPONSE_MIN_DETECTION_CONFIDENCE`를 검증 evidence로 확정합니다.
- [x] 이 detector는 classifier를 사용하지 않으며 `classifierVersion`과 `classificationConfidence`를 null로 유지합니다. `RESPONSE_MIN_CLASSIFICATION_CONFIDENCE`는 설정하지 않습니다.
- [ ] class score, count/confidence score와 LOW/MEDIUM/HIGH risk 경계를 승인합니다.
- [ ] 필요할 때만 `RESPONSE_MIN_RISK_LEVEL`을 설정합니다.
- [x] 실제 `.pt`를 로드한 `/health/ready`가 `inference=model`, `runtime=ultralytics-8.4.125`, bundle/model version을 반환합니다.
- [x] synthetic JPEG의 실제 model application smoke가 성공했습니다. 이 결과는 연결 경로만 검증하며 `RISK_POLICY_CONFIRMED=true`와 `RESPONSE_POLICY_ENABLED=true`의 승인은 별도입니다.

`MODEL_SMOKE=PASS`의 범위는 checkpoint hash, bundle load, synthetic JPEG 추론, adapter 변환, readiness와 Backend event handoff입니다. 실제 야생동물 정확도·현장 threshold는 `NOT_VERIFIED`이며 Mock 결과도 실제 model 품질 증거로 사용하지 않습니다. 미완료 policy 항목과 이슈 상태는 별도로 판정합니다.

## Raspberry Pi 연결 시

- [ ] broker host/port와 TLS 사용 여부를 확정합니다.
- [ ] TLS 전용 broker라면 Backend의 `tcp://` URI와 Pi의 non-TLS client를 그대로
  사용하지 않고, 양쪽 TLS/인증서 설정 구현과 검증을 먼저 완료합니다.
- [ ] username/password를 secret store 또는 배포 환경에 넣고 저장소에 커밋하지 않습니다.
- [ ] stable `deviceId`를 Backend mapping, MQTT topic과 controller 설정에서 동일하게 사용합니다.
- [ ] `cameraId -> deviceId` mapping을 실제 camera inventory와 대조합니다.
- [ ] motor IN1/IN2/sleep, servo, speaker BCM pin을 실제 배선표로 확인합니다.
- [ ] servo min/max/step과 motor/sound maximum duration을 module 사양으로 확인합니다.
- [ ] camera 해상도·FPS·JPEG 설정과 AI Server URL을 확정합니다.
- [ ] Pi의 NTP 동기화를 확인해 정상 command가 장치 시계 때문에 만료되지 않게 합니다.
- [ ] camera-uploader와 embedded controller systemd unit, working directory, env/secret 권한과 restart policy를 검증합니다.
- [ ] ONLINE/OFFLINE LWT, 다섯 semantic command, ACKNOWLEDGED/terminal ACK smoke를 실제 broker에서 실행합니다.
- [ ] duplicate commandId, expired command와 STOP_DETERRENT가 실제 GPIO를 중복/지연 없이 처리하는지 확인합니다.
- [ ] latest-frame uploader의 30FPS capture 실측과 upload backpressure/drop 동작을 기록합니다.

이 입력과 실제 장치 evidence가 없으면 `HARDWARE_SMOKE=NOT_RUN`, `HARDWARE_MQTT_SMOKE=NOT_RUN`입니다. embedded code가 merge돼도 이슈 #26은 hardware smoke 전까지 열어 둘 수 있습니다.

## 작동 활성화 전

- [ ] `GET /api/v1/actuation/preflight`가 `enabled=true`, `ready=true`, `blockers=[]`인지 확인합니다.
- [ ] MQTT client가 connected이고 ACK/status SUBACK가 모두 완료됐는지 확인합니다.
- [ ] response allowlist/confidence와 risk policy가 실제 model evidence와 일치하는지 확인합니다.
- [ ] 모든 production cameraId mapping과 target deviceId를 대조합니다.
- [ ] operator API 필요 여부, token rotation·배포·로그 비노출을 확인합니다.
- [ ] manual/automatic STOP을 실제 장치와 broker에서 검증합니다.
- [ ] duplicate와 expiry가 GPIO를 재실행하지 않고 Backend terminal state에 반영되는지 확인합니다.
- [ ] FAILED/EXPIRED marker reconciliation 뒤 다음 event가 재평가되는지 확인합니다.
- [ ] production 기본 false gate를 한 번에 우회하지 않고 readiness 순서대로 활성화합니다.

## 아직 운영 전 별도 검토

- [ ] broker·HTTP TLS와 인증서 수명주기
- [ ] broker topic ACL과 device별 publish/subscribe 최소 권한
- [ ] multi-instance observation/command lock 또는 단일 active instance 운영 보장
- [ ] production secret store, rotation과 접근 감사
- [ ] Backend/AI/MQTT/device monitoring과 alert 기준
- [ ] PostgreSQL·SQLite backup/restore와 retention
- [ ] Backend, broker, DB와 device fleet high availability/재해복구
- [ ] outbox/inbox 또는 delivery recovery가 필요한지에 대한 운영 결정

## 반복 검증 명령

```bash
bash scripts/e2e/animalguard_mock_e2e.sh
```

PASS는 `MOCK_E2E=PASS`와 `FLYWAY_FINAL_VERSION=5`를 모두 포함해야 합니다. Docker/process 권한으로 실행할 수 없으면 완료가 아니라 `BLOCKED_E2E_ENVIRONMENT`입니다. Mock E2E와 component test가 모두 PASS하고 clean `develop`, open PR 0일 때만 `SOFTWARE_READY_FOR_MODEL_AND_RASPBERRY_PI=YES`를 판정합니다.
