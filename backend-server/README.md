# Backend Server

AnimalGuard의 Java 17·Spring Boot 3 Backend 서버입니다. Detection Event v1과 RiskDecision을 저장하고 camera별 aggregate animal observation으로 automatic semantic command를 선택합니다. 현재 observation policy는 RiskDecision을 감사 기록으로만 사용하며 risk score/level로 command 요청 여부나 type을 결정하지 않습니다. classCode별 response policy는 이슈 #5의 운영 점수 확정과 별도로 승인할 후속 범위입니다.

## API

```text
POST /api/v1/detection/events
```

요청은 `model`과 `detections`를 포함합니다. 각 탐지는 `classCode`를 사용하며 `eventId`가 이미 저장되어 있으면 `409 Conflict`를 반환합니다.

정상 응답은 기존 `eventId`, `riskScore`, `riskLevel`, 선택적 `commandId`를 유지하면서 `commandOutcome`과 `commandBlockers`를 항상 포함합니다.

- `NOT_REQUESTED`: 이번 event에서 상태만 갱신했거나 stale event를 무시해 command intent가 없습니다.
- `CREATED`: 관찰 policy가 요청한 DeviceCommand row가 `CREATED`로 저장됐고 `commandId`가 포함됩니다.
- `SUPPRESSED`: 관찰 policy의 command intent가 안전 gate 또는 운영 조건으로 억제됐으며 하나 이상의 blocker가 포함됩니다.

현재 suppression 판정은 Detection Event API 응답과 event당 한 건의 Backend 명령 판정 로그로만 진단합니다. DB에는 별도 저장하지 않으므로 durable audit이 필요하면 별도 schema와 migration을 설계해야 합니다. DB 저장 실패나 잘못된 Entity 불변식 같은 시스템 오류는 `SUPPRESSED`로 변환하지 않습니다.

## 동물 관찰 설정과 State Machine

```yaml
animalguard:
  observation:
    persistence-threshold: ${ANIMAL_PERSISTENCE_THRESHOLD:5s}
    absence-grace: ${ANIMAL_ABSENCE_GRACE:2s}
    continuity-timeout: ${ANIMAL_CONTINUITY_TIMEOUT:3s}
    sound-alert-duration: ${SOUND_ALERT_DURATION:2s}
    deterrent-full-duration: ${DETERRENT_FULL_DURATION:5s}
```

기본값은 MVP 검증용이며 운영값이 아닙니다. 모든 값은 양수이고 continuity timeout은 absence grace 이상이어야 하며 두 command duration은 양수 int 밀리초로 변환 가능해야 합니다. `detections`가 하나 이상이면 classCode, confidence, 개체 수와 관계없이 하나의 aggregate presence입니다. 별도 최소 confidence나 classCode allowlist는 적용하지 않으므로 API validation을 통과한 `UNKNOWN` 저신뢰 탐지도 command intent를 만들 수 있습니다. sequence와 frame 수는 사용하지 않고 event의 `capturedAt`만으로 지속·grace·continuity를 계산합니다.

```text
IDLE
  │ positive
  ▼
PRESENT
  ├─ first detection       → SOUND_ALERT
  ├─ persistence threshold → DETERRENT_FULL
  ├─ brief absence         → grace 유지
  └─ disappearance         → STOP_DETERRENT 또는 IDLE
```

`capturedAt <= lastProcessedCapturedAt`인 stale event도 DetectionEvent와 RiskDecision은 저장하지만 observation과 command를 변경하지 않습니다. full deterrent 전 event gap이 continuity timeout을 초과하면 새 session으로 시작하고, full deterrent marker가 있으면 gap만으로 장치 상태나 동물 사라짐을 추측하지 않습니다. 새 Detection Event가 오지 않으면 전이를 평가하지 않으므로 카메라·AI·네트워크가 완전히 멈춘 경우 Backend가 STOP을 새로 생성할 scheduler는 없습니다.

## 위험도 설정

`application.yml`의 `animalguard.risk`에서 class score, 탐지 수 threshold와 점수, confidence threshold와 점수, LOW/MEDIUM/HIGH 경계를 설정합니다. 설정 범위를 벗어난 값이나 역전된 위험도 경계는 애플리케이션 시작 시 거부됩니다. 이 값들은 현재 저장·응답하는 RiskDecision만 바꾸며 event별 command eligibility를 gate하지 않습니다. 운영 기본 class score는 현재 `MAGPIE: 30`, `UNKNOWN: 0`만 정의하며 다른 유해동물의 운영 점수는 확정하지 않았습니다. 따라서 이슈 #5에서 점수만 확정해도 현재 작동 조건은 달라지지 않으며, risk/class 기반 작동 조건은 별도 response policy 결정이 필요합니다.

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

`AnimalObservationService`가 실제 Detection Event, cameraId, semantic type, duration과 reason을 `DeviceCommandCreationService`에 전달합니다. persistence가 충족되면 미생성 SOUND_ALERT보다 DETERRENT_FULL을 우선합니다. command가 SUPPRESSED되면 session timestamp는 저장하되 marker는 저장하지 않아 다음 event에서 재평가합니다.

명시적인 자동 command 요청이 들어오면 같은 device의 최신 `AUTOMATIC` command만 조회합니다.

- 최신 automatic command가 없거나 requested type과 다르면 semantic transition을 허용합니다.
- 같은 type이고 `latest.createdAt + cooldown > now`이면 `COOLDOWN_ACTIVE`입니다.
- `STOP_DETERRENT`는 cooldown을 우회하고 최근 MANUAL command는 automatic cooldown에 영향을 주지 않습니다.

따라서 이 cooldown은 같은 type의 빠른 반복만 막으며 command type과 무관한 장치별 최소 명령 간격은 보장하지 않습니다. 같은 PRESENT session의 marker가 SOUND_ALERT와 DETERRENT_FULL 중복을 막고, marker는 command duration이 끝나도 만료되지 않으므로 동물이 계속 감지되는 한 DETERRENT_FULL도 한 번만 생성됩니다. 반복 퇴치 주기는 현재 정책에 없습니다.

`IDLE`에서는 `AUTOMATIC`/`CREATED` command를 저장하고 응답에 `commandId`를 포함합니다. 새 command는 호출자가 선택한 semantic type과 원본 `reason`, 서버 Clock의 `issuedAt`, `issuedAt + commandTtl`인 `expiresAt`을 저장하며 DB record 생성 시각 `createdAt`은 현재 MVP에서 `issuedAt`과 같습니다. reason이 500자를 넘으면 truncate하지 않고 생성을 거절합니다. `COOLDOWN`에서는 `SUPPRESSED/COOLDOWN_ACTIVE`, 매핑되지 않은 cameraId에서는 `SUPPRESSED/CAMERA_UNMAPPED`를 반환하고 DeviceCommand를 만들지 않습니다. cameraId를 deviceId로 fallback하지 않습니다.

`animal_observation_states`는 camera별 IDLE/PRESENT와 session timestamp, CREATED command marker를 저장합니다. marker는 실제 publish, ACK 또는 GPIO 실행을 뜻하지 않습니다. ACK에서 FAILED/EXPIRED가 된 뒤 marker를 재설정하는 reconciliation은 아직 없습니다. cooldown의 source of truth는 `device_commands.created_at`이며 observation duration과 달리 Backend Clock 기준입니다.

현재 observation gate와 command gate는 단일 Backend 인스턴스에서 전역 fair lock을 transaction 완료까지 유지하며 항상 observation → command 순서로 획득합니다. 따라서 다른 camera/device 판단도 선행 transaction 완료까지 대기합니다. `@Version`은 lost update를 감지하지만 다중 Backend 인스턴스의 완전한 원자성이나 자동 optimistic-lock retry는 제공하지 않습니다.

## 실제 장치 작동 preflight

`animalguard.actuation`은 실제 DeviceCommand 생성 허용 여부와 운영 위험 정책 확정 여부를 명시합니다.

```yaml
animalguard:
  actuation:
    enabled: ${ACTUATION_ENABLED:false}
    risk-policy-confirmed: ${RISK_POLICY_CONFIRMED:false}
```

두 값의 기본값은 모두 false입니다. 이슈 #5가 완료되기 전에는 `risk-policy-confirmed=false`를 유지하며 현재 `MAGPIE`, `UNKNOWN` 점수 설정만으로 운영 정책이 확정됐다고 추정하지 않습니다. `risk-policy-confirmed`는 운영자가 명시적으로 해제하는 global readiness gate이며 개별 event의 risk score/level을 판정하는 gate가 아닙니다. `ActuationTransportReadiness`는 MQTT enabled와 실제 client connection을 함께 요구하며 운영 property로 ready를 강제할 수 없습니다.

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
| `MQTT_PUBLISHER_NOT_READY` | MQTT가 disabled이거나 client가 disconnected | 억제 | broker·배포 설정 |
| `CAMERA_UNMAPPED` | 요청 cameraId가 mapping에 없음 | 억제 | 배포 설정 |
| `COOLDOWN_ACTIVE` | 최신 command의 cooldown이 끝나지 않음 | 억제 | 시간 경과 |

Preflight endpoint는 앞의 네 global blocker를 표 순서대로 모두 수집하는 일반 actuation 시작 readiness 의미를 유지합니다. SOUND_ALERT와 DETERRENT_FULL은 이 global gate를 사용합니다. safety-stop인 STOP_DETERRENT는 `ACTUATION_DISABLED`, `RISK_POLICY_UNCONFIRMED`, `COOLDOWN_ACTIVE`를 우회하지만 `CAMERA_DEVICE_MAPPING_EMPTY`, `MQTT_PUBLISHER_NOT_READY`, 특정 `CAMERA_UNMAPPED`는 여전히 적용합니다. 이 endpoint는 Backend 전체 health나 AI 모델 readiness를 대신하지 않습니다.

## MQTT Publisher와 transport readiness

Backend는 Eclipse Paho MQTT v3 client `1.2.5` 하나만 사용합니다. Spring Integration MQTT나 다른 MQTT client를 함께 사용하지 않습니다. 이 버전은 Eclipse Paho Java 저장소의 최신 v3 service release이며 Maven Central artifact를 명시적으로 pin한 값입니다.

```yaml
animalguard:
  mqtt:
    enabled: ${MQTT_ENABLED:false}
    host: ${MQTT_HOST:127.0.0.1}
    port: ${MQTT_PORT:1883}
    client-id: ${MQTT_CLIENT_ID:animalguard-backend}
    username: ${MQTT_USERNAME:}
    password: ${MQTT_PASSWORD:}
    connect-timeout: ${MQTT_CONNECT_TIMEOUT:5s}
    publish-timeout: ${MQTT_PUBLISH_TIMEOUT:5s}
    dispatch-interval: ${MQTT_DISPATCH_INTERVAL:500ms}
    dispatch-batch-size: ${MQTT_DISPATCH_BATCH_SIZE:20}
```

port는 1~65535, client ID와 host는 non-blank, timeout과 interval은 양수, batch size는 양수여야 합니다. username과 password는 빈 값일 수 있지만 실제 credential을 tracked 설정에 넣지 않습니다. 설정 객체 문자열과 Backend 로그에는 password를 출력하지 않습니다.

기본 `MQTT_ENABLED=false`에서는 애플리케이션이 broker 없이 시작하고 연결, subscribe, publish를 시도하지 않으며 transport readiness는 false입니다. enabled 상태의 readiness는 Paho client가 실제로 connected이고 ACK와 status subscription의 SUBACK가 모두 완료된 경우에만 true입니다. 별도 property로 readiness를 강제로 true로 만들 수 없습니다. 초기 broker 연결 실패도 애플리케이션 시작을 실패시키지 않으며 scheduler가 다시 연결을 시도합니다. Paho automatic reconnect도 활성화하고 reconnect callback마다 두 topic을 다시 subscribe합니다. 연결과 subscription 경고는 분류된 원인만 최대 30초에 한 번 기록하고 password나 반복 stack trace를 포함하지 않습니다.

command topic은 다음 형식입니다.

```text
animalguard/devices/{encodedDeviceId}/commands
```

`deviceId`는 UTF-8 RFC3986 percent encoding을 사용합니다. 영문, 숫자, `-`, `.`, `_`, `~`는 유지하고 `/`, `+`, `#`, `%`, 공백과 그 밖의 byte는 `%HH`로 인코딩합니다. form encoding의 `+`를 공백 의미로 사용하지 않습니다. payload의 원본 `deviceId`와 topic segment를 decode한 값은 같아야 합니다.

Publisher JSON은 Entity를 직접 serialize하지 않고 MQTT 전용 DTO를 사용합니다. `commandId`, `eventId`, `deviceId`, `source`, `command`, `durationMs`, `issuedAt`, `expiresAt`, `reason` 아홉 필드를 항상 포함하며 null인 `eventId`와 `durationMs`도 생략하지 않습니다. 현재 PR은 `AUTOMATIC` command만 dispatch하므로 eventId는 Detection Event UUID입니다. MANUAL payload DTO 계약은 준비돼 있지만 실제 생성·dispatch는 수동 API PR 범위입니다.

단일 scheduler thread가 `CREATED/AUTOMATIC` command를 `createdAt`, DB id 오름차순으로 batch 조회합니다. 이 Publisher 도입 전에 생성된 row도 동일 조건을 만족하면 대상입니다. 각 command는 reload 후 source/status, source별 preflight, 현재 camera-device mapping에 대상 deviceId가 남아 있는지, `expiresAt`, payload 계약을 다시 확인합니다.

- `now >= expiresAt`: `EXPIRED(now, reportedAt=null)`로 commit하고 publish하지 않습니다.
- preflight blocked: `CREATED`를 유지해 설정·연결이 준비된 이후 재평가하고 publish하지 않습니다.
- payload 계약 오류: programming/data invariant 오류로 별도 기록하고 publish하지 않습니다.
- 정상: `PUBLISHED(now)`와 payload를 같은 transaction에서 준비하고 commit한 뒤 QoS 1, retain=false로 한 번 publish합니다.

`PUBLISHED`는 broker delivery 증명이 아니라 dispatch가 승인돼 publish 호출을 시작했다는 뜻입니다. transaction commit 뒤 transport를 호출하므로 즉시 도착하는 후속 ACK가 `CREATED`를 관찰하지 않습니다. Paho publish가 즉시 실패하면 별도 transaction에서 현재 상태가 여전히 `PUBLISHED`인 command만 `FAILED(now, reportedAt=null)`로 바꿉니다. publish timeout, disconnect, optimistic-lock 충돌을 이유로 MQTT command를 자동 재발행하지 않습니다.

process가 `PUBLISHED` commit 후 publish 전이나 호출 도중 종료되면 delivery 없이 `PUBLISHED`가 남을 수 있습니다. 이 crash window와 PUBLISHED/ACKNOWLEDGED timeout은 reconciliation PR 책임입니다. manual command dispatch, TLS/ACL, outbox, multi-instance coordination도 현재 범위에 포함하지 않습니다.

ACK/status Subscriber는 QoS 1으로 `animalguard/devices/+/acks`와 `animalguard/devices/+/status`를 구독합니다. ACK는 commandId, deviceId, status와 상태에 맞는 timestamp 정확히 하나만 허용하고 status 보고는 deviceId, status, reportedAt, firmwareVersion 정확히 네 필드만 허용합니다. unknown field, 잘못된 timestamp field, timezone 없는 시각과 topic/payload/DB deviceId 불일치는 상태 변경 전에 거절합니다.

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

`src/main/resources/db/migration`의 Flyway migration이 schema 변경의 source of truth입니다. V1은 baseline, V2는 DeviceCommand 전달 lifecycle, V3는 source/manual 계약, V4는 unique cameraId와 optimistic-lock version을 가진 `animal_observation_states`를 추가합니다. V5는 기존 `device_statuses`의 connected, last_seen, temperature와 duplicate deviceId를 보존하면서 operational_status, firmware_version, reported_at, received_at, version을 추가합니다. nullable command marker는 command 실행 lifecycle과 observation lifecycle을 불필요하게 결합하지 않도록 FK를 두지 않습니다. Flyway는 모든 프로필에서 활성화되고 `baseline-on-migrate=false`, `clean-disabled=true`가 기본값입니다.

V5는 기존 connected=true를 ONLINE, false를 OFFLINE으로 backfill하고 reported_at에는 last_seen, received_at에는 `COALESCE(last_seen, CURRENT_TIMESTAMP)`를 사용합니다. 새 status 보고는 장치 시각을 reportedAt, Backend `Clock`을 receivedAt과 legacy lastSeen에 저장합니다. ONLINE, DEGRADED, MAINTENANCE는 connected=true이고 OFFLINE만 false입니다. deviceId가 unique가 아니므로 같은 deviceId의 row가 여러 개면 `receivedAt DESC, id DESC`의 최신 row 하나를 갱신하고 없으면 새 row를 생성합니다.

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

## MQTT delivery와 ACK/status Subscriber

| 다음 작업 | 사용할 field/method |
| --- | --- |
| Publisher | `issuedAt`, `expiresAt`, `reason`, `markPublished` — 현재 production dispatcher가 사용 |
| ACKNOWLEDGED | `markAcknowledged(receivedAt, reportedAt)` |
| EXECUTED | `markExecuted(receivedAt, reportedAt)` |
| FAILED | `markFailed(receivedAt, reportedAt)` |
| EXPIRED | `markExpired(receivedAt, reportedAt)` |

현재 Publisher는 dispatch 직전에 `ActuationPreflightService`를 다시 평가하고 `CREATED` command의 `expiresAt`을 검사합니다. preflight가 blocked이면 `CREATED`를 유지하고, 만료됐으면 `EXPIRED`로 commit하며 둘 다 publish하지 않습니다. 통과한 command는 주입된 Clock의 시각으로 `markPublished`하고 transaction을 commit한 뒤 MQTT client를 호출합니다. 여기서 `PUBLISHED`는 broker 전달 증명이 아니라 dispatch가 승인되어 publish 시도가 시작되었다는 뜻입니다. 이 순서로 즉시 도착할 후속 ACK가 아직 `CREATED`인 row를 읽는 경쟁을 막습니다. MQTT 호출이 즉시 실패하면 후속 transaction에서 `FAILED`로 기록합니다.

기존 `acknowledged_at`, `executed_at`, `failed_at`, `expired_at`은 Backend가 ACK를 받은 시각 또는 자체 실패·만료를 결정한 시각입니다. ACK payload의 장치 시각은 각각 `acknowledged_reported_at`, `executed_reported_at`, `failed_reported_at`, `expired_reported_at`에 별도로 저장합니다. 상태 순서는 Backend 시각끼리만 비교하고 장치 시각과 Backend 시각을 직접 비교하지 않습니다. ACKNOWLEDGED와 EXECUTED의 장치 보고 시각은 필수이고, Backend 자체 실패·만료에는 장치 보고 시각이 없을 수 있습니다.

ACK Subscriber는 ACK의 의미에 따라 transition method를 호출합니다. `CREATED → PUBLISHED → ACKNOWLEDGED → EXECUTED`, `PUBLISHED/ACKNOWLEDGED → FAILED`, `CREATED/PUBLISHED → EXPIRED`만 허용하며 같은 상태의 QoS 1 중복 적용은 최초 Backend/장치 timestamp를 모두 보존하는 no-op입니다. ACKNOWLEDGED가 이미 EXECUTED까지 진행된 경우도 멱등하게 무시합니다. 순서를 건너뛰거나 terminal 상태를 바꾸는 ACK는 Entity를 변경하기 전에 거절합니다. `@Version` 충돌 시 현재 row를 reload하고 같은 상태·advanced 상태·terminal conflict를 재평가하며, 아직 동일 ACK가 명확히 적용 가능한 경우에만 저장을 1회 재시도합니다. 두 번째 충돌은 오류로 기록하고 추가 retry나 MQTT command 재발행을 하지 않습니다.

현재 production code에서 `DeviceCommandCreationService`가 preflight를 통과한 `CREATED`를 만들고, `DeviceCommandDispatcher`와 `DeviceCommandDispatchCoordinator`가 `markPublished`, 자체 만료의 `markExpired`, 즉시 publish 실패의 `markFailed`를 호출합니다. `DeviceCommandAckHandler`는 `markAcknowledged`, `markExecuted`와 장치가 보고한 FAILED/EXPIRED를 실제로 적용합니다. V2 migration은 MQTT 이력이 없는 legacy row를 `EXPIRED`로 전환합니다.

다음 reconciliation PR은 PUBLISHED crash window, PUBLISHED/ACKNOWLEDGED timeout과 FAILED/EXPIRED command의 observation marker를 처리합니다. 현재는 outbox/inbox, publish retry, 다중 Backend 인스턴스, broker 인증/TLS와 장애 복구를 해결하지 않습니다. 실제 GPIO 실행 코드, AI 모델과 classCode별 위험 정책도 구현하지 않습니다.
