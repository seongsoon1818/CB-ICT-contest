# Backend Server

AnimalGuard의 Java 17·Spring Boot 3 Backend 서버입니다. Detection Event v1을 검증해 PostgreSQL에 저장하고, 설정 기반 Risk Engine으로 RiskDecision을 생성하며 HIGH일 때 장치별 cooldown gate를 통과한 DeviceCommand를 기록합니다.

## API

```text
POST /api/v1/detection/events
```

요청은 `model`과 `detections`를 포함합니다. 각 탐지는 `classCode`를 사용하며 `eventId`가 이미 저장되어 있으면 `409 Conflict`를 반환합니다.

정상 응답은 기존 `eventId`, `riskScore`, `riskLevel`, 선택적 `commandId`를 유지하면서 `commandOutcome`과 `commandBlockers`를 항상 포함합니다.

- `NOT_REQUESTED`: LOW/MEDIUM이라 명령 생성 대상이 아니며 blocker가 없습니다.
- `CREATED`: DeviceCommand가 저장됐고 기존 `commandId`가 포함됩니다.
- `SUPPRESSED`: HIGH이지만 안전 gate 또는 운영 조건 때문에 명령을 만들지 않았으며 하나 이상의 blocker가 포함됩니다.

현재 suppression 판정은 Detection Event API 응답과 event당 한 건의 Backend 명령 판정 로그로만 진단합니다. DB에는 별도 저장하지 않으므로 durable audit이 필요하면 별도 schema와 migration을 설계해야 합니다. DB 저장 실패나 잘못된 Entity 불변식 같은 시스템 오류는 `SUPPRESSED`로 변환하지 않습니다.

## 위험도 설정

`application.yml`의 `animalguard.risk`에서 class score, 탐지 수 threshold와 점수, confidence threshold와 점수, LOW/MEDIUM/HIGH 경계를 설정합니다. 설정 범위를 벗어난 값이나 역전된 위험도 경계는 애플리케이션 시작 시 거부됩니다. 운영 기본 class score는 현재 `MAGPIE: 30`, `UNKNOWN: 0`만 정의하며 다른 유해동물의 운영 점수는 확정하지 않았습니다.

## 장치 명령 설정과 cooldown

`animalguard.device-control`에서 cooldown, command TTL과 `cameraId` → `deviceId` 매핑을 설정합니다.

```yaml
animalguard:
  device-control:
    cooldown: 20s
    command-ttl: 10s
    camera-device-mappings:
      cam-001: pi-001
```

cooldown과 command TTL은 모두 양수여야 하고 command TTL은 cooldown보다 길 수 없습니다. `DEVICE_COMMAND_TTL`의 기본값은 `10s`입니다. 이 관계는 아직 유효한 두 command가 같은 장치에서 겹치는 설정을 막습니다. command TTL은 Raspberry Pi가 새 command를 실행할 수 있는 payload 유효 기간입니다. `durationMs`는 `SOUND_ALERT`와 `DETERRENT_FULL`의 작동 시간이며, 회전과 `STOP_DETERRENT`에서는 null이므로 TTL과 다른 값입니다. cameraId는 Detection Event와 같은 식별자 형식을 사용하고 deviceId는 비어 있을 수 없습니다. 여러 cameraId가 같은 deviceId를 가리키는 설정은 허용합니다. cameraId에 점이 포함된 key를 명시적으로 보존하려면 YAML에서 `"[cam.001]": pi-001`처럼 대괄호를 포함한 key 전체를 따옴표로 묶습니다. 운영 기본 설정에는 실제 장치 매핑을 넣지 않고 빈 map을 사용하며, `local` 프로필에만 `cam-001: pi-001` 예시가 있습니다.

`DeviceCommandCreationService`는 향후 자동 response policy가 실제 Detection Event, cameraId, `DeviceCommandType`, reason을 명시적으로 전달할 때만 호출합니다. risk level만 보고 command를 추측하지 않으며, 현재 `DetectionEventService`는 관찰 state machine과 policy가 없으므로 HIGH를 포함한 모든 이벤트에서 RiskDecision까지만 저장하고 command는 `NOT_REQUESTED`로 둡니다.

명시적인 자동 command 요청이 들어오면 매핑된 deviceId의 최신 현재-contract `device_commands` 기록으로 상태를 계산합니다.

- 최신 command가 없으면 `IDLE`입니다.
- `latest.createdAt + cooldown > now`이면 `COOLDOWN`입니다.
- `latest.createdAt + cooldown <= now`이면 다시 `IDLE`입니다.

`IDLE`에서는 `AUTOMATIC`/`CREATED` command를 저장하고 응답에 `commandId`를 포함합니다. 새 command는 호출자가 선택한 semantic type과 원본 `reason`, 서버 Clock의 `issuedAt`, `issuedAt + commandTtl`인 `expiresAt`을 저장하며 DB record 생성 시각 `createdAt`은 현재 MVP에서 `issuedAt`과 같습니다. reason이 500자를 넘으면 truncate하지 않고 생성을 거절합니다. `COOLDOWN`에서는 `SUPPRESSED/COOLDOWN_ACTIVE`, 매핑되지 않은 cameraId에서는 `SUPPRESSED/CAMERA_UNMAPPED`를 반환하고 DeviceCommand를 만들지 않습니다. cameraId를 deviceId로 fallback하지 않습니다.

별도 상태 테이블이나 scheduler는 없습니다. `device_commands.created_at`이 source of truth이므로 애플리케이션 재시작 후에도 다음 이벤트에서 cooldown을 다시 계산합니다. cooldown은 카메라의 `capturedAt`이 아니라 Backend가 실제 command를 생성한 서버 시각을 기준으로 하며, 지연되거나 순서가 뒤바뀐 Detection Event가 장치 명령 간격을 왜곡하지 않도록 합니다.

현재 command gate는 단일 Backend 인스턴스에서 모든 mapped device의 자동 command 판단을 하나의 전역 gate로 transaction 완료까지 직렬화합니다. 따라서 선행 transaction이 완료되기 전에는 다른 device의 command 판단도 대기합니다. 다중 인스턴스 배포 전에는 DB 기반 원자적 gate 또는 분산 lock 검토가 필요합니다.

## 실제 장치 작동 preflight

`animalguard.actuation`은 실제 DeviceCommand 생성 허용 여부와 운영 위험 정책 확정 여부를 명시합니다.

```yaml
animalguard:
  actuation:
    enabled: ${ACTUATION_ENABLED:false}
    risk-policy-confirmed: ${RISK_POLICY_CONFIRMED:false}
```

두 값의 기본값은 모두 false입니다. 이슈 #5가 완료되기 전에는 `risk-policy-confirmed=false`를 유지하며 현재 `MAGPIE`, `UNKNOWN` 점수 설정만으로 운영 정책이 확정됐다고 추정하지 않습니다. 실제 MQTT Publisher가 없는 현재 기본 `ActuationTransportReadiness`도 항상 false입니다. readiness를 운영 property로 우회할 수 없으며 다음 MQTT PR이 실제 연결 상태를 제공하는 bean으로 교체합니다.

```text
GET /api/v1/actuation/preflight
```

blocked 상태도 진단 요청 자체는 성공했으므로 `200 OK`입니다.

```json
{
  "enabled": false,
  "ready": false,
  "blockers": [
    "ACTUATION_DISABLED",
    "RISK_POLICY_UNCONFIRMED",
    "CAMERA_DEVICE_MAPPING_EMPTY",
    "MQTT_PUBLISHER_NOT_READY"
  ]
}
```

| Blocker | 판정 근거 | 명령 생성 동작 | 해제 주체 |
| --- | --- | --- | --- |
| `ACTUATION_DISABLED` | `enabled=false` | 억제 | 운영 설정 |
| `RISK_POLICY_UNCONFIRMED` | `risk-policy-confirmed=false` | 억제 | 이슈 #5와 팀 합의 |
| `CAMERA_DEVICE_MAPPING_EMPTY` | 전체 mapping이 비어 있음 | 억제 | 배포 설정 |
| `MQTT_PUBLISHER_NOT_READY` | transport readiness가 false | 억제 | 다음 MQTT PR |
| `CAMERA_UNMAPPED` | 요청 cameraId가 mapping에 없음 | 억제 | 배포 설정 |
| `COOLDOWN_ACTIVE` | 최신 command의 cooldown이 끝나지 않음 | 억제 | 시간 경과 |

Preflight는 앞의 네 global blocker를 표 순서대로 모두 수집합니다. 명시적인 자동 command 요청은 lock을 잡기 전에 global preflight를 평가하고, ready일 때만 특정 camera mapping과 cooldown을 차례로 확인합니다. 현재 Detection Event 수신 경로는 자동 policy가 없어 command 생성 서비스를 호출하지 않으므로 risk level과 무관하게 `NOT_REQUESTED`입니다. 이 endpoint는 Backend 전체 health나 AI 모델 readiness를 대신하지 않습니다. 안전 정지인 `STOP_DETERRENT`의 최종 blocker 우회 정책은 Publisher/manual API 후속 작업에서 확정하며, 이번 단계에서는 기존 preflight를 우회하지 않습니다.

## 로컬 실행

저장소 루트에서 PostgreSQL을 먼저 실행합니다.

```bash
docker compose -f infra/docker-compose.yml up -d postgres
cd backend-server
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로필은 로컬 datasource 기본값을 제공하며 기본 database, username, password는 `animalguard`입니다. 모든 프로필은 Flyway migration으로 schema를 만든 뒤 Hibernate `ddl-auto=validate`로 Entity 정합성만 검증합니다. 테스트는 외부 PostgreSQL 없이 H2 PostgreSQL mode를 사용하지만 schema는 Hibernate `create-drop`이 아니라 동일한 Flyway migration으로 생성합니다.

```bash
./gradlew test
```

## 데이터베이스 schema 관리

`src/main/resources/db/migration`의 Flyway migration이 schema 변경의 source of truth입니다. `V1__baseline_animalguard_schema.sql`은 도입 시점의 AnimalGuard JPA Entity 다섯 테이블을 만들고, `V2__prepare_device_command_mqtt_delivery.sql`은 DeviceCommand 전달 column과 `(device_id, created_at)` index를 추가합니다. Flyway는 모든 프로필에서 활성화되고 `baseline-on-migrate=false`, `clean-disabled=true`가 기본값이므로 migration 이력이 없는 non-empty schema를 정상 schema로 조용히 간주하지 않으며 migration 실패 시 애플리케이션 시작도 실패합니다.

V2 이전의 command에는 MQTT payload field와 실제 publish 이력이 없습니다. V2는 이 row를 삭제하거나 publish 가능한 `CREATED`로 남기지 않고 `status=EXPIRED`, `reason=LEGACY_PRE_MQTT_COMMAND`, `issued_at=expires_at=expired_at=created_at`으로 backfill한 뒤 required column을 `NOT NULL`로 전환합니다. 이 equality는 새 command 생성자의 `expiresAt > issuedAt` 규칙을 만족하는 정상 command를 합성하려는 것이 아니라, publish할 수 없는 legacy row를 migration 전용 terminal tombstone으로 보존한다는 뜻입니다. 장치 보고 시각은 알 수 없으므로 네 `*_reported_at` column은 `NULL`, optimistic-lock `version`은 `0`으로 시작합니다.

지원하는 기본 경로는 빈 AnimalGuard DB에서 V1부터 적용하는 방식입니다.

기존 Hibernate `ddl-auto=update` 경로로 만든 local DB처럼 table은 있지만 `flyway_schema_history`가 없는 schema에서는 다음 오류로 시작을 중단합니다.

```text
Found non-empty schema(s) "public" but no schema history table. Use baseline() or set baselineOnMigrate to true to initialize the schema history table.
```

이 메시지의 제안과 달리 `baseline-on-migrate=true`를 켜서 우회하지 않습니다. Flyway가 기존 schema를 V1과 같다고 검증하지 않은 채 migration 이력만 만들 수 있기 때문입니다.

- 중요한 데이터가 없는 기존 local volume은 먼저 필요한 내용을 백업한 뒤 `animalguard-postgres-data` volume을 삭제하고 fresh DB로 재생성합니다.
- 기존 데이터를 반드시 보존해야 하면 먼저 백업하고 현재 schema가 V1과 정확히 같은지 수동으로 확인한 뒤, 별도로 승인된 일회성 baseline 절차를 사용합니다. `baseline-on-migrate=true`는 local 기본값이 아니며 자동 도입이나 안전한 이전을 보장하지 않습니다.
- BirdGuard 이름이 남은 schema, `bird_detections`가 있는 schema, `classification_confidence`가 `NOT NULL`인 오래된 schema, 알 수 없는 수동 변경이 있는 schema는 자동 도입 대상이 아닙니다. 실제 이전 요구가 확인되면 별도 migration 작업으로 다룹니다.

기본 프로필은 datasource 환경 변수 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 요구합니다. 기존 BirdGuard DB에 이 애플리케이션을 직접 기동하지 않습니다.

`classification_confidence` nullable 변경 전에 생성한 `animalguard-postgres-data` volume은 V1과 schema가 일치하지 않아 자동 도입 대상이 아닙니다. 필요한 데이터를 백업한 뒤 volume을 재생성해야 합니다.

## 다음 Backend MQTT PR handoff

| 다음 작업 | 사용할 field/method |
| --- | --- |
| Publisher | `issuedAt`, `expiresAt`, `reason`, `markPublished` |
| ACKNOWLEDGED | `markAcknowledged(receivedAt, reportedAt)` |
| EXECUTED | `markExecuted(receivedAt, reportedAt)` |
| FAILED | `markFailed(receivedAt, reportedAt)` |
| EXPIRED | `markExpired(receivedAt, reportedAt)` |

다음 MVP Publisher는 dispatch 직전에 `ActuationPreflightService`를 다시 평가하고 `CREATED` command의 `expiresAt`을 검사합니다. preflight가 blocked이거나 command가 만료됐으면 publish하지 않습니다. 통과한 command는 주입된 Clock의 시각으로 `markPublished`하고 transaction을 commit한 뒤 MQTT client를 호출합니다. 여기서 `PUBLISHED`는 broker 전달 증명이 아니라 dispatch가 승인되어 publish 시도가 시작되었다는 뜻입니다. 이 순서로 즉시 도착한 ACK가 아직 `CREATED`인 row를 읽는 경쟁을 막습니다. MQTT 호출이 즉시 실패하면 후속 transaction에서 `FAILED`로 기록합니다.

기존 `acknowledged_at`, `executed_at`, `failed_at`, `expired_at`은 Backend가 ACK를 받은 시각 또는 자체 실패·만료를 결정한 시각입니다. ACK payload의 장치 시각은 각각 `acknowledged_reported_at`, `executed_reported_at`, `failed_reported_at`, `expired_reported_at`에 별도로 저장합니다. 상태 순서는 Backend 시각끼리만 비교하고 장치 시각과 Backend 시각을 직접 비교하지 않습니다. ACKNOWLEDGED와 EXECUTED의 장치 보고 시각은 필수이고, Backend 자체 실패·만료에는 장치 보고 시각이 없을 수 있습니다.

ACK Subscriber는 ACK의 의미에 따라 transition method를 호출합니다. `CREATED → PUBLISHED → ACKNOWLEDGED → EXECUTED`, `PUBLISHED/ACKNOWLEDGED → FAILED`, `CREATED/PUBLISHED → EXPIRED`만 허용하며 같은 상태의 QoS 1 중복 적용은 최초 Backend/장치 timestamp를 모두 보존하는 no-op입니다. 순서를 건너뛰거나 terminal 상태를 바꾸거나 이전 Backend timestamp를 적용하면 Entity를 변경하기 전에 거절합니다. `@Version`은 동시 writer의 lost update를 감지합니다. 후속 handler는 optimistic-lock 충돌 시 상태를 다시 읽고 같은 상태나 이미 진행된 상태는 멱등 처리하며 충돌하는 terminal 상태는 거절하고 기록해야 합니다. DB 충돌을 이유로 MQTT publish를 자동 재시도해서는 안 됩니다.

현재 production code에서 `DeviceCommandCreationService`가 preflight를 통과한 `CREATED`를 만들고 V2 migration이 legacy row를 `EXPIRED`로 전환합니다. `markPublished`, `markAcknowledged`, `markExecuted`, `markFailed`, `markExpired`는 현재 domain test로만 검증되며 실제 호출자는 다음 Publisher/ACK Subscriber PR에서 추가합니다.

다음 Publisher PR은 이 기능 도입 전에 존재하는 `CREATED` command를 조회할지, 만료 처리할지, 어떤 범위부터 dispatch할지 명시적으로 결정해야 합니다. 이번 단계에서는 그 처리 정책, `PUBLISHED` reconciliation, 오래된 `ACKNOWLEDGED` reconciliation, outbox/inbox, publish retry, 다중 Backend 인스턴스, broker 인증/TLS와 장애 복구를 해결하지 않습니다. 실제 MQTT Publisher/Subscriber, GPIO 실행 코드, AI 모델과 위험 점수 정책도 구현하지 않습니다.
