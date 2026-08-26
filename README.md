# AnimalGuard

AnimalGuard는 카메라 기반 유해동물 탐지·분류 결과를 활용해 농작물 피해 위험도를 판단하고, 필요한 경우에만 비살상 대응 장치를 작동시키는 AI 농업 보호 시스템입니다.

## 해결하려는 문제

상시 작동하는 대응 장치는 불필요한 전력과 운영을 발생시키고 유익한 생물까지 방해할 수 있습니다. AnimalGuard는 탐지 결과와 위험도 판단을 바탕으로 필요한 장치 동작만 생성하는 것을 목표로 합니다.

## 시스템 흐름

감지 → AI 분석 → Backend 판단 → MQTT → Raspberry Pi

## 팀 역할

- AI 데이터: 유해동물 데이터 수집, 라벨링, 탐지·분류 모델 학습
- Backend/서버: AI 결과 수신·저장, 위험도 판단, 이벤트 관리, MQTT 명령 생성
- 임베디드: Raspberry Pi에서 의미 기반 명령을 장치 동작으로 매핑
- 지원: 프로젝트 문서와 통합 지원

## 저장소 디렉터리

- ai-server: FastAPI MockInference와 model bundle/runtime 경계
- backend-server: Spring Boot Detection Event 수신·저장 및 위험도 판단
- raspberry-pi: Raspberry Pi 장치의 MQTT/GPIO 경계
- models: 향후 모델 산출물 보관 위치. 모델 바이너리는 커밋하지 않음
- infra: 로컬 PostgreSQL·Mosquitto 실행 구성

## 현재 구현 상태

Backend는 `POST /api/v1/detection/events`로 Detection Event v1을 받아 이벤트와 탐지, 모델 버전, 위험도 판단을 저장합니다. 모델 독립 response policy의 class allowlist, detection/classification confidence와 선택적 minimum risk level을 통과한 detection만 동물별 구분 없는 camera별 aggregate presence에 참여합니다. 통과하지 못한 detection도 DetectionEvent와 전체 detection 기반 RiskDecision 감사 기록에는 남습니다. frame 개수나 sequence 대신 `capturedAt`으로 첫 감지, 지속 감지, 짧은 미탐지 grace와 사라짐을 판단합니다. 첫 감지는 `SOUND_ALERT`, 지속 threshold는 `DETERRENT_FULL`, full deterrent가 생성된 session의 사라짐은 `STOP_DETERRENT`를 요청합니다. preflight나 cooldown으로 억제되면 command marker를 남기지 않고 다음 event에서 재평가합니다. `GET /api/v1/actuation/preflight`는 response policy와 MQTT 연결·ACK/status subscription을 포함한 일반 actuation 시작 readiness를 진단합니다.

Backend는 MQTT command Publisher와 ACK/status Subscriber를 구현해 command 상태 전이와 장치 상태 저장까지 연결합니다. `POST /api/v1/devices/{deviceId}/commands`는 기본 비활성 shared operator token 경계 뒤에서 등록된 장치의 수동 회전과 safety-stop 명령을 만들며, 자동·수동 `CREATED` 명령은 publish 직전에 source별 preflight를 다시 검사합니다. timeout과 FAILED/EXPIRED marker reconciliation, no-event watchdog도 fail-closed scheduler로 처리합니다. Raspberry Pi embedded controller는 최종 MQTT v1 topic·payload·SQLite dedup·GPIO adapter 경계에 정렬됐지만 실제 hardware smoke는 별도입니다. 안전 기본값으로 actuation, 위험 정책 확정 여부, response policy와 operator API는 false이고 MQTT disabled 상태의 readiness도 false이므로 production 기본 설정에서는 실제 DeviceCommand 생성이 차단됩니다.

## 로컬 실행

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필은 로컬 datasource 기본값을 제공하며 기본 PostgreSQL database, username, password는 모두 `animalguard`이고 volume은 `animalguard-postgres-data`입니다. schema는 모든 프로필에서 Flyway migration으로 만들고 Hibernate는 `ddl-auto=validate`로 검증만 합니다.

지원 경로는 빈 AnimalGuard DB에서 V1부터 `V5__extend_device_status_reporting.sql`까지 순서대로 적용하는 것입니다. V4는 camera별 `animal_observation_states`, V5는 기존 `device_statuses`를 보존하면서 operational status와 장치/Backend 시각 및 optimistic-lock version을 추가합니다. 중요한 데이터가 없는 기존 local volume은 필요한 내용을 백업한 뒤 삭제하고 fresh DB로 재생성합니다. 데이터를 보존해야 하면 schema가 V1과 정확히 같은지 수동 확인하고 별도로 승인된 일회성 baseline 절차를 사용해야 하며 `baseline-on-migrate=true`는 기본값이 아닙니다.

BirdGuard 이름이 남은 schema, `bird_detections`가 있는 schema, `classification_confidence`가 `NOT NULL`인 오래된 schema, 알 수 없는 수동 변경이 있는 schema는 자동 도입하지 않습니다. 기존 BirdGuard DB에 직접 기동하지 않으며 실제 데이터 이전 요구는 별도 migration 작업으로 다룹니다. 자세한 정책은 `backend-server/README.md`를 참고합니다.

```bash
cd backend-server
./gradlew test
```

## Mock 전체 E2E

실제 모델과 Raspberry Pi 대신 AI `MockInference`와 MQTT Simulator를 사용해 JPEG → DetectionEvent → observation → command → ACK → Backend terminal state를 격리된 PostgreSQL·Mosquitto에서 검증합니다.

```bash
bash scripts/e2e/animalguard_mock_e2e.sh
```

스크립트는 고유 Compose project와 임시 volume·process를 만들고 종료 시 정리합니다. 첫 감지, 지속 감지, brief miss, 사라짐, duplicate, expiry, publish failure reconciliation, 수동 API와 response eligibility를 검증합니다. 이 PASS는 실제 model inference, Raspberry Pi GPIO, camera 30FPS 또는 production broker를 증명하지 않습니다. 연결 전 입력과 활성화 순서는 [`docs/SOFTWARE_READY_CHECKLIST.md`](docs/SOFTWARE_READY_CHECKLIST.md)를 따릅니다.
