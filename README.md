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

- ai-server: 향후 FastAPI 기반 탐지·분류 추론 서버 경계
- backend-server: Spring Boot Detection Event 수신·저장 및 위험도 판단
- raspberry-pi: Raspberry Pi 장치의 MQTT/GPIO 경계
- models: 향후 모델 산출물 보관 위치. 모델 바이너리는 커밋하지 않음
- infra: 로컬 PostgreSQL 실행 구성

## 현재 구현 상태

Backend는 `POST /api/v1/detection/events`로 Detection Event v1을 받아 이벤트와 탐지, 모델 버전, 위험도 판단을 저장합니다. 모든 accepted detection을 동물별 구분 없는 camera별 aggregate presence로 처리하고 frame 개수나 sequence 연속성 대신 `capturedAt`으로 첫 감지, 지속 감지, 짧은 미탐지 grace와 사라짐을 판단합니다. 현재 RiskDecision은 감사 기록이며 risk score/level은 command 요청 여부를 gate하지 않습니다. 별도 최소 confidence나 classCode allowlist도 없으므로 API validation을 통과한 detection 하나면 presence가 됩니다. 첫 감지는 `SOUND_ALERT`, 지속 threshold는 `DETERRENT_FULL`, full deterrent가 생성된 session의 사라짐은 `STOP_DETERRENT`를 요청합니다. preflight나 cooldown으로 억제되면 command marker를 남기지 않고 다음 event에서 재평가합니다. `GET /api/v1/actuation/preflight`는 일반 actuation 시작 readiness를 진단하며 실제 MQTT 연결은 아직 없습니다.

AI Server의 실제 모델, classCode별 response policy, Backend MQTT Publisher/ACK Subscriber, retry/outbox, ROI 계산은 아직 구현하지 않았습니다. 이슈 #5의 운영 위험 점수 확정만으로 현재 작동 조건이 달라지지는 않으며 risk/class 기반 작동 조건은 별도 정책 결정이 필요합니다. observation marker는 DeviceCommand row가 `CREATED`로 저장됐다는 뜻이며 실제 GPIO 실행 완료를 뜻하지 않습니다. marker는 command duration 종료로 만료되지 않아 같은 PRESENT session의 DETERRENT_FULL은 한 번만 생성됩니다. ACK 실패 후 marker reconciliation과 event가 완전히 끊겼을 때 동작할 scheduler도 없습니다. `raspberry-pi/embedded` 운영 계약 정렬은 이슈 #26 범위입니다. 안전 기본값으로 actuation과 위험 정책 확정 여부는 false이고 기본 MQTT Publisher readiness도 false이므로 production 기본 설정에서는 STOP을 포함한 실제 DeviceCommand 생성이 transport readiness에서 차단됩니다.

## 로컬 실행

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필은 로컬 datasource 기본값을 제공하며 기본 PostgreSQL database, username, password는 모두 `animalguard`이고 volume은 `animalguard-postgres-data`입니다. schema는 모든 프로필에서 Flyway migration으로 만들고 Hibernate는 `ddl-auto=validate`로 검증만 합니다.

지원 경로는 빈 AnimalGuard DB에서 V1부터 `V4__add_animal_observation_state.sql`까지 순서대로 적용하는 것입니다. V4는 camera별 `animal_observation_states`와 optimistic-lock version을 추가하고 command marker에는 FK를 두지 않습니다. 중요한 데이터가 없는 기존 local volume은 필요한 내용을 백업한 뒤 삭제하고 fresh DB로 재생성합니다. 데이터를 보존해야 하면 schema가 V1과 정확히 같은지 수동 확인하고 별도로 승인된 일회성 baseline 절차를 사용해야 하며 `baseline-on-migrate=true`는 기본값이 아닙니다.

BirdGuard 이름이 남은 schema, `bird_detections`가 있는 schema, `classification_confidence`가 `NOT NULL`인 오래된 schema, 알 수 없는 수동 변경이 있는 schema는 자동 도입하지 않습니다. 기존 BirdGuard DB에 직접 기동하지 않으며 실제 데이터 이전 요구는 별도 migration 작업으로 다룹니다. 자세한 정책은 `backend-server/README.md`를 참고합니다.

```bash
cd backend-server
./gradlew test
```
