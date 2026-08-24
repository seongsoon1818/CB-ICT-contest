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

Backend는 `POST /api/v1/detection/events`로 Detection Event v1을 받아 이벤트와 탐지, 모델 버전, 위험도 판단을 저장합니다. HIGH 위험도일 때만 DeviceCommand를 생성하며 중복 eventId는 `409 Conflict`로 거절합니다.

AI Server, 실제 모델, MQTT/GPIO 실행 코드, State Machine/cooldown, ROI 계산, DB migration framework는 아직 구현하지 않았습니다.

## 로컬 실행

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필에서만 Hibernate schema update를 기본 사용합니다. 기본 로컬 PostgreSQL database, username, password는 모두 `animalguard`이며 volume은 `animalguard-postgres-data`입니다. 기존 로컬 기본값의 테스트 데이터는 자동 이전되지 않으므로 필요한 경우 기존 volume을 수동으로 정리한 뒤 새 환경을 시작해야 합니다.

```bash
cd backend-server
./gradlew test
```
