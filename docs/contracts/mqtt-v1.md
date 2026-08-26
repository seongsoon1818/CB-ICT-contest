# Raspberry Pi MQTT v1 Contract

## Scope

This document defines the Backend-to-device command boundary and the Raspberry Pi acknowledgement, status, and sensor-event topics. Backend sends semantic commands; Raspberry Pi owns the mapping from a semantic command to GPIO actions.

Backend never sends GPIO pin numbers.

## Topics

The topic prefix is animalguard.

| Topic | Direction | Purpose | Default delivery |
| --- | --- | --- | --- |
| animalguard/devices/{deviceId}/commands | Backend → Raspberry Pi | Semantic device commands | QoS 1, retain false |
| animalguard/devices/{deviceId}/acks | Raspberry Pi → Backend | Command processing result | QoS 1 |
| animalguard/devices/{deviceId}/status | Raspberry Pi → Backend | Device health and state | QoS 1 |
| animalguard/devices/{deviceId}/sensor-events | Raspberry Pi → Backend | Sensor observations | QoS 1 |

deviceId is the stable identifier in the topic and payload. Topic path values must be safely encoded by the eventual MQTT client.

## Command payload

Automatic example:

~~~json
{
  "commandId": "command-001",
  "eventId": "15356786-9588-4db4-a0fe-f8acd6300868",
  "deviceId": "pi-001",
  "source": "AUTOMATIC",
  "command": "SOUND_ALERT",
  "durationMs": 2000,
  "issuedAt": "2026-08-26T00:00:00Z",
  "expiresAt": "2026-08-26T00:00:10Z",
  "reason": "FIRST_ANIMAL_DETECTION"
}
~~~

Manual example:

~~~json
{
  "commandId": "manual-15356786-9588-4db4-a0fe-f8acd6300868",
  "eventId": null,
  "deviceId": "pi-001",
  "source": "MANUAL",
  "command": "ROTATE_CAMERA_LEFT",
  "durationMs": null,
  "issuedAt": "2026-08-26T00:00:00Z",
  "expiresAt": "2026-08-26T00:00:10Z",
  "reason": "USER_REQUEST"
}
~~~

Rules:

- commandId is a unique, durable idempotency key. The same commandId must not cause a second physical actuation.
- source is either AUTOMATIC or MANUAL. It is stored independently and is never inferred from command because STOP_DETERRENT supports both sources.
- eventId is a required Detection Event UUID for AUTOMATIC and must be null for MANUAL. A manual command never creates or references a fake Detection Event.
- command must be one of ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT, SOUND_ALERT, DETERRENT_FULL, or STOP_DETERRENT. It is a semantic device action, not a GPIO instruction.
- durationMs is a positive integer for SOUND_ALERT and DETERRENT_FULL. It must be null for ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT, and STOP_DETERRENT; callers must not invent a duration for a duration-free command.
- issuedAt and expiresAt use ISO 8601 / RFC 3339 date-time with an explicit timezone.
- expiresAt must be later than issuedAt. Raspberry Pi must not execute a command after expiresAt.
- reason is an auditable decision basis, not a device-control parameter. Automatic examples include FIRST_ANIMAL_DETECTION, PERSISTENT_ANIMAL_DETECTION, and ANIMAL_DISAPPEARED; the manual value is USER_REQUEST. Future Backend policy may provide a more detailed risk-decision reason.
- Command messages use QoS 1 by default and are not retained. A retained command could be executed unexpectedly after reconnect.

Allowed source and command combinations:

| Source | Command | durationMs | Raspberry Pi semantic action |
| --- | --- | --- | --- |
| AUTOMATIC | SOUND_ALERT | positive integer | Speaker alert |
| AUTOMATIC | DETERRENT_FULL | positive integer | Deterrent motor and speaker on |
| AUTOMATIC | STOP_DETERRENT | null | Deterrent motor and speaker off |
| MANUAL | ROTATE_CAMERA_LEFT | null | Camera servo 5 degrees left |
| MANUAL | ROTATE_CAMERA_RIGHT | null | Camera servo 5 degrees right |
| MANUAL | STOP_DETERRENT | null | Deterrent motor and speaker off |

Animal-specific commands such as DETERRENT_MAGPIE, DETERRENT_BOAR, or DETERRENT_DEER are forbidden. Backend response eligibility를 통과한 detection은 하나의 aggregate presence로 처리해 SOUND_ALERT → DETERRENT_FULL → STOP_DETERRENT를 선택합니다. classCode와 confidence는 eligibility에만 사용하며 MQTT vocabulary는 Raspberry Pi가 수행할 semantic action만 표현합니다.

## ACK payload

~~~json
{
  "commandId": "cmd-a272f7cc",
  "deviceId": "pi-001",
  "status": "EXECUTED",
  "executedAt": "2026-08-21T07:00:04Z"
}
~~~

The ACK status is one of ACKNOWLEDGED, EXECUTED, FAILED, or EXPIRED. The payload contains exactly one matching device-reported timestamp: `acknowledgedAt`, `executedAt`, `failedAt`, or `expiredAt`. A device publishes an ACK after processing a command. If a duplicate commandId is received, the device does not actuate again; it may publish the previously recorded ACK again.

Device-reported timestamps and Backend receipt timestamps are different clocks. Backend stores the device value in the matching `*_reported_at` column and its own receipt/decision time in `acknowledged_at`, `executed_at`, `failed_at`, or `expired_at`. State ordering uses only Backend timestamps; it never compares a Raspberry Pi clock directly with the Backend clock. A device timestamp earlier than `published_at` is therefore valid when the ACK is received in the correct Backend state.

## Status payload

Minimum example:

~~~json
{
  "deviceId": "pi-001",
  "status": "ONLINE",
  "reportedAt": "2026-08-21T07:00:04Z",
  "firmwareVersion": "pi-firmware-v1"
}
~~~

status is device-scoped and may be ONLINE, OFFLINE, DEGRADED, or MAINTENANCE. reportedAt is the device time with timezone; Backend records its received_at separately.

## Sensor event payload

Minimum example:

~~~json
{
  "deviceId": "pi-001",
  "sensorType": "MOTION",
  "value": true,
  "observedAt": "2026-08-21T07:00:02Z"
}
~~~

value may be a boolean or a scalar appropriate to the sensor type. sensorType and observedAt are required. Backend must retain the deviceId scope and server received time.

## Command state machine

A command can move through these states:

CREATED → PUBLISHED → ACKNOWLEDGED → EXECUTED

Failure or expiry can occur before execution:

- PUBLISHED or ACKNOWLEDGED → FAILED when processing or delivery fails.
- CREATED or PUBLISHED → EXPIRED when the expiry time passes before execution.
- EXECUTED, FAILED, and EXPIRED are terminal for that command.

Before dispatch, Backend applies the source-appropriate policy, target mapping, expiry, and safety checks. Automatic commands require a real Detection Event. Manual commands require a valid operator token at creation, an enabled operator API at dispatch, and no Detection Event. MQTT itself does not bypass Backend policy.

For the MVP Publisher, `PUBLISHED` means dispatch was authorized and the publish attempt began; it does not prove broker delivery. Backend commits `PUBLISHED` before invoking the MQTT client so an immediate ACK cannot observe a `CREATED` row. An immediate publish failure is recorded as `FAILED`. A process crash after the commit but before or during the MQTT call can leave `PUBLISHED` without delivery; the reconciliation scheduler marks a stale PUBLISHED or ACKNOWLEDGED command FAILED without MQTT retry.

## Scope and safety rules

- Risk and cooldown state is not global. It is managed per camera or per device according to the source of the decision and target device.
- Backend does not send GPIO numbers, PWM values, or raw actuator wiring instructions.
- Raspberry Pi maps the semantic command to local GPIO behavior and rejects expired commands before actuation.
- QoS 1 provides at-least-once delivery, so commandId deduplication is mandatory.
- Backend uses optimistic locking for concurrent command-state writers. A database optimistic-lock conflict must not trigger another MQTT publish; the handler reloads state and treats the same or already-advanced result as idempotent, while conflicting terminal state is rejected and logged.
- Commands are not retained. The retain policy for status and sensor events may be finalized with the broker deployment, but it must not change command idempotency or expiry semantics.
- ACK, status, and sensor-event timestamps include timezones; Backend stores server receipt times for audit.
- The manual Backend API has a shared operator-token boundary. User identity, role-based authorization, TLS, and broker ACL configuration remain deployment or follow-up concerns.

STOP_DETERRENT is a safety-stop command. Automatic STOP creation bypasses ACTUATION_DISABLED, RISK_POLICY_UNCONFIRMED, and COOLDOWN_ACTIVE, while device target mapping, MQTT transport readiness, commandId, and expiry remain required. Manual STOP additionally requires the operator API and a valid operator token at creation, but also bypasses actuation, risk/response policy, and cooldown. The public actuation preflight endpoint still describes general actuation-start readiness. The Backend Publisher preserves the source-appropriate reduced STOP gate when it rechecks dispatch safety.

Backend transport readiness requires an enabled MQTT client, an active broker connection, and successful ACK and status subscriptions. It resets both subscription markers on connection loss and re-subscribes after reconnect. A connection without both SUBACK results is not actuation-ready.

Backend strictly rejects ACK and status payloads with unknown fields, a wrong ACK timestamp field, a timezone-less reported timestamp, or a topic/payload/DB deviceId mismatch. It records device-reported time separately from Backend receipt time. Existing duplicate `device_statuses.device_id` rows are preserved; a new report updates the latest row by Backend `received_at` then id, or creates a row when none exists.

Observation command markers mean only that a DeviceCommand row was created. They do not prove PUBLISHED, ACKNOWLEDGED, EXECUTED, GPIO activation, or GPIO stop. The reconciliation scheduler clears FAILED/EXPIRED markers so a later event can re-evaluate, expires stale CREATED commands, fails timed-out PUBLISHED/ACKNOWLEDGED commands, and runs the no-event watchdog. It does not infer broker delivery or retry a command.

## Mock E2E evidence boundary

`scripts/e2e/animalguard_mock_e2e.sh` verifies this contract with isolated PostgreSQL and Mosquitto, the real Backend publisher/subscriber, AI MockInference and the Raspberry Pi MQTT Simulator. It covers all five semantic commands, QoS 1 duplicate deduplication, device-reported ACK state changes, expiry and immediate publish-failure reconciliation. The fault proxy is test-only and drops one armed Backend PUBLISH before broker forwarding; it does not add a production transport mode.

Mock E2E PASS does not prove model inference, physical GPIO, real Raspberry Pi clock/wiring, broker authentication/TLS or network reliability. The embedded controller has separate FakeGPIO tests, while real command/ACK/GPIO smoke remains hardware evidence.
