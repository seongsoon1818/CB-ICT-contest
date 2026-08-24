# Backend Server

AnimalGuard의 Java 17·Spring Boot 3 Backend 서버입니다. Detection Event v1을 검증해 PostgreSQL에 저장하고, 설정 기반 Risk Engine으로 RiskDecision을 생성하며 HIGH일 때만 DeviceCommand를 기록합니다.

## API

```text
POST /api/v1/detection/events
```

요청은 `model`과 `detections`를 포함합니다. 각 탐지는 `classCode`를 사용하며 `eventId`가 이미 저장되어 있으면 `409 Conflict`를 반환합니다.

## 위험도 설정

`application.yml`의 `animalguard.risk`에서 class score, 탐지 수 threshold와 점수, confidence threshold와 점수를 설정합니다. 운영 기본 class score는 현재 `MAGPIE: 30`, `UNKNOWN: 0`만 정의하며 다른 유해동물의 운영 점수는 확정하지 않았습니다.

## 로컬 실행

저장소 루트에서 PostgreSQL을 먼저 실행합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
./gradlew bootRun
```

기본 database, username, password는 `animalguard`입니다. 테스트는 외부 PostgreSQL 없이 H2의 `animalguard` 메모리 데이터베이스를 사용합니다.

```bash
./gradlew test
```

로컬 개발 DB 기본 이름과 volume 이름이 변경되므로 기존 테스트 데이터는 자동 이전되지 않습니다. 필요한 경우 기존 로컬 volume을 수동으로 정리한 뒤 새 환경을 시작해야 합니다.

이번 단계에서는 AI Server, 실제 모델, MQTT/GPIO 실행 코드, State Machine/cooldown, ROI 계산, DB migration framework를 구현하지 않습니다.
