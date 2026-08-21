# Backend Server

Java 17과 Spring Boot 3 기반 Backend 서버입니다. 이번 Sprint 1에서는 Mock Detection Event를 검증하고 PostgreSQL에 저장하며 RiskDecision과 HIGH 시 DeviceCommand를 함께 기록합니다.

## API

```text
POST /api/v1/detection/events
```

요청 JSON은 Phase 0에서 정의한 Detection Event 구조를 따릅니다. `eventId`가 이미 저장되어 있으면 `409 Conflict`를 반환합니다.

## 로컬 실행

저장소 루트에서 PostgreSQL을 먼저 실행합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
./gradlew bootRun
```

테스트는 외부 PostgreSQL 없이 H2 테스트 데이터베이스를 사용합니다.

```bash
./gradlew test
```

이번 단계에서는 MQTT 전송, AI Server, YOLO, Raspberry Pi, Dashboard를 구현하지 않습니다.
