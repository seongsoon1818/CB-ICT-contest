# Backend Server

AnimalGuard의 Java 17·Spring Boot 3 Backend 서버입니다. Detection Event v1을 검증해 PostgreSQL에 저장하고, 설정 기반 Risk Engine으로 RiskDecision을 생성하며 HIGH일 때 장치별 cooldown gate를 통과한 DeviceCommand를 기록합니다.

## API

```text
POST /api/v1/detection/events
```

요청은 `model`과 `detections`를 포함합니다. 각 탐지는 `classCode`를 사용하며 `eventId`가 이미 저장되어 있으면 `409 Conflict`를 반환합니다.

## 위험도 설정

`application.yml`의 `animalguard.risk`에서 class score, 탐지 수 threshold와 점수, confidence threshold와 점수, LOW/MEDIUM/HIGH 경계를 설정합니다. 설정 범위를 벗어난 값이나 역전된 위험도 경계는 애플리케이션 시작 시 거부됩니다. 운영 기본 class score는 현재 `MAGPIE: 30`, `UNKNOWN: 0`만 정의하며 다른 유해동물의 운영 점수는 확정하지 않았습니다.

## 장치 명령 설정과 cooldown

`animalguard.device-control`에서 cooldown과 `cameraId` → `deviceId` 매핑을 설정합니다.

```yaml
animalguard:
  device-control:
    cooldown: 20s
    camera-device-mappings:
      cam-001: pi-001
```

cooldown은 양수여야 하며, cameraId는 Detection Event와 같은 식별자 형식을 사용하고 deviceId는 비어 있을 수 없습니다. 여러 cameraId가 같은 deviceId를 가리키는 설정은 허용합니다. 운영 기본 설정에는 실제 장치 매핑을 넣지 않고 빈 map을 사용하며, `local` 프로필에만 `cam-001: pi-001` 예시가 있습니다.

HIGH 위험 이벤트가 들어오면 매핑된 deviceId의 최신 `device_commands` 기록으로 상태를 계산합니다.

- 최신 command가 없으면 `IDLE`입니다.
- `latest.createdAt + cooldown > now`이면 `COOLDOWN`입니다.
- `latest.createdAt + cooldown <= now`이면 다시 `IDLE`입니다.

`IDLE`에서는 `CREATED` command를 저장하고 응답에 `commandId`를 포함합니다. `COOLDOWN`에서는 새 command와 응답의 `commandId`만 생략하고 DetectionEvent, AnimalDetection, RiskDecision은 그대로 저장합니다. 매핑되지 않은 cameraId도 같은 감사 기록을 저장하되 cameraId를 deviceId로 fallback하지 않고 WARN 로그와 함께 command를 억제합니다. LOW/MEDIUM 이벤트는 command를 만들지 않으며 기존 cooldown을 갱신하지 않습니다.

별도 상태 테이블이나 scheduler는 없습니다. `device_commands.created_at`이 source of truth이므로 애플리케이션 재시작 후에도 다음 이벤트에서 cooldown을 다시 계산합니다. command 생성 판단과 저장은 작은 전용 gate에서 transaction 완료까지 직렬화합니다.

현재 command gate는 단일 Backend 인스턴스 기준이다. 다중 인스턴스 배포 전에는 DB 기반 원자적 gate 또는 분산 lock 검토가 필요하다.

## 로컬 실행

저장소 루트에서 PostgreSQL을 먼저 실행합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필에서만 로컬 datasource 기본값과 Hibernate schema update를 사용합니다. 기본 database, username, password는 `animalguard`입니다. 테스트는 외부 PostgreSQL 없이 H2의 `animalguard` 메모리 데이터베이스를 사용합니다.

기본 프로필은 datasource 환경 변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 요구하고 기존 스키마를 변경하지 않은 채 `ddl-auto=validate`로 검증합니다. 이번 단계에는 baseline·rename·backfill migration이 없으므로 non-local 배포를 지원하지 않습니다. migration 수단을 마련하기 전에는 기존 BirdGuard DB에 이 애플리케이션을 직접 기동하지 않습니다.

```bash
./gradlew test
```

로컬 개발 DB 기본 이름과 volume 이름이 변경되므로 기존 테스트 데이터는 자동 이전되지 않습니다. 필요한 경우 기존 로컬 volume을 수동으로 정리한 뒤 새 환경을 시작해야 합니다.

`classification_confidence` nullable 변경 전에 생성한 `animalguard-postgres-data` volume은 Hibernate schema update가 기존 `NOT NULL` 제약을 제거하지 않으므로, 필요한 데이터를 백업한 뒤 volume을 재생성해야 합니다.

이번 단계에서는 AI Server, 실제 모델, MQTT Publisher/Subscriber, ACK/status 전이, GPIO 실행 코드, 전체 추적 State Machine, ROI 계산, DB migration framework를 구현하지 않습니다. DeviceCommand 상태는 `CREATED`만 사용합니다.
