# Backend Mock Detection Event Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Execute this plan task-by-task with verification checkpoints.

**Goal:** Spring Boot Backend가 Mock Detection Event를 검증·저장하고 RiskDecision을 계산하며 HIGH일 때 CREATED DeviceCommand를 저장하도록 만든다.

**Architecture:** `DetectionEventController → DetectionEventService → RiskDecisionEngine/Repositories` 흐름을 유지한다. 요청 DTO는 Bean Validation으로 경계를 검사하고, JPA 엔티티는 DetectionEvent를 부모로 하여 BirdDetection·RiskDecision·DeviceCommand를 PostgreSQL에 저장한다. 이번 단계의 command는 DB에만 저장하며 MQTT 전송은 없다.

**Tech Stack:** Java 17 source compatibility, Spring Boot 3, Gradle, Spring Web, Spring Data JPA, Validation, PostgreSQL Driver, Lombok, H2 test runtime.

## Global Constraints

- `POST /api/v1/detection/events`만 구현한다.
- `eventId`는 UNIQUE로 저장하고 중복 요청은 HTTP 409를 반환한다.
- confidence는 0.0 이상 1.0 이하, bbox 좌표는 음수 금지, bbox 크기와 image 크기는 양수다.
- insideField는 입력에 없으므로 false로 취급하고 이번 점수에는 반영하지 않는다.
- RiskLevel 경계는 LOW `[0,40)`, MEDIUM `[40,70)`, HIGH `[70,100]`이다.
- HIGH인 경우에만 DeviceCommand를 `CREATED`로 저장하며 MQTT는 호출하지 않는다.
- AI Server, YOLO, PyTorch, FastAPI, MQTT, Raspberry Pi, Dashboard, 모델 파일은 추가하지 않는다.

---

### Task 1: Spring Boot scaffold and persistence model

**Files:**
- Create: `backend-server/settings.gradle`
- Create: `backend-server/build.gradle`
- Create: `backend-server/gradlew`
- Create: `backend-server/gradlew.bat`
- Create: `backend-server/gradle/wrapper/gradle-wrapper.properties`
- Create: `backend-server/src/main/java/com/birdguard/BirdGuardApplication.java`
- Create: `backend-server/src/main/java/com/birdguard/domain/*.java`
- Create: `backend-server/src/main/java/com/birdguard/repository/*.java`
- Create: `backend-server/src/main/resources/application.yml`
- Create: `backend-server/src/test/resources/application-test.yml`

**Implementation:**

- Configure Spring Boot 3, Java 17 toolchain/source compatibility, Web, JPA, Validation, Lombok, PostgreSQL, and H2 test runtime.
- Create `DetectionEvent`, `BirdDetection`, `RiskDecision`, `DeviceCommand`, and `DeviceStatus` entities with generated IDs, timestamps, required columns, event foreign keys, and unique constraints for `event_id` and `command_id`.
- Use enums `RiskLevel { LOW, MEDIUM, HIGH }` and `DeviceCommandStatus { CREATED }` with `EnumType.STRING`.
- Configure PostgreSQL defaults through `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`; use `ddl-auto=update` for this local sprint and H2 `create-drop` in the test profile.

**Verification:**

Run `./gradlew test` after the scaffold and confirm the application context starts.

### Task 2: Request boundary and vertical-slice service

**Files:**
- Create: `backend-server/src/main/java/com/birdguard/dto/DetectionEventRequest.java`
- Create: `backend-server/src/main/java/com/birdguard/dto/DetectionEventResponse.java`
- Create: `backend-server/src/main/java/com/birdguard/service/RiskDecisionEngine.java`
- Create: `backend-server/src/main/java/com/birdguard/service/DetectionEventService.java`
- Create: `backend-server/src/main/java/com/birdguard/exception/DuplicateDetectionEventException.java`
- Create: `backend-server/src/main/java/com/birdguard/exception/ApiExceptionHandler.java`

**Implementation:**

- Model the nested `image`, `birds`, and `bbox` request objects as records with `@Valid` and exact validation annotations.
- Map a valid request to the event and bird entities inside one transaction.
- Calculate `MAGPIE +30`, `birds.size() >= 3 +20`, and a detection/classification pair both at least `0.9 +20`; clamp the score to 0..100 and classify it using the fixed boundaries.
- Store one RiskDecision per event and create a deterministic mock-target command only for HIGH, with `CREATED` status and no MQTT call.
- Return eventId, riskScore, riskLevel, and nullable commandId; map validation to 400 and duplicates to 409.

**Verification:**

Run focused integration tests for a valid event, HIGH command creation, validation failures, and duplicate event IDs.

### Task 3: Controller and PostgreSQL Compose service

**Files:**
- Create: `backend-server/src/main/java/com/birdguard/controller/DetectionEventController.java`
- Create: `infra/docker-compose.yml`
- Modify: `backend-server/README.md`
- Modify: `infra/README.md`

**Implementation:**

- Expose `POST /api/v1/detection/events`, accept JSON, apply `@Valid`, and return 201 for a stored event.
- Define only a `postgres` Compose service with a named volume, healthcheck, and local development environment variables.
- Document the backend test/start commands and PostgreSQL-only scope.

**Verification:**

Run `docker compose -f infra/docker-compose.yml config` and confirm the output contains only the PostgreSQL service.

### Task 4: Tests and release checks

**Files:**
- Create: `backend-server/src/test/java/com/birdguard/controller/DetectionEventControllerIntegrationTest.java`

**Implementation:**

- Verify 201 plus persisted DetectionEvent/RiskDecision for a valid event.
- Verify HIGH input creates exactly one DeviceCommand with `CREATED` status.
- Verify confidence > 1, negative bbox, and missing eventId return 400.
- Verify the second identical eventId request returns 409 and does not create another DetectionEvent.

**Verification:**

Run `./gradlew test`, `docker compose -f infra/docker-compose.yml config`, `git diff --check`, and scans proving no secrets or model binaries were added. Inspect `git status` and the final diff before committing.

### Task 5: Commit and Draft PR

**Implementation:**

- Stage only the confirmed Backend Sprint 1 files.
- Commit with `feat: Backend Mock Detection Event 수신 및 위험도 판단 구현`.
- Push `feature/backend-mock-detection-vertical-slice` and create a Draft PR targeting `develop` with a Korean body that states PR #1 is the preceding Phase 0 contract work and lists verification evidence and explicit exclusions.

**Verification:**

Read back the remote branch, commit hash, and PR metadata (`base=develop`, `draft=true`) before reporting them.
