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

~~~json
{
  "commandId": "cmd-a272f7cc",
  "eventId": "15356786-9588-4db4-a0fe-f8acd6300868",
  "deviceId": "pi-001",
  "command": "DETERRENT_LEVEL_2",
  "durationMs": 5000,
  "issuedAt": "2026-08-21T07:00:03Z",
  "expiresAt": "2026-08-21T07:00:13Z",
  "reason": "CLASS_SCORE_MAGPIE +30, DETECTION_COUNT_GE_3 +20"
}
~~~

Rules:

- commandId is a unique, durable idempotency key. The same commandId must not cause a second physical actuation.
- eventId is the Detection Event UUID that caused the command.
- command is semantic, such as DETERRENT_LEVEL_1, DETERRENT_LEVEL_2, DETERRENT_LEVEL_3, or STOP_DETERRENT. It is not a GPIO instruction.
- durationMs is a positive duration in milliseconds.
- issuedAt and expiresAt use ISO 8601 / RFC 3339 date-time with an explicit timezone.
- expiresAt must be later than issuedAt. Raspberry Pi must not execute a command after expiresAt.
- reason is the detailed, auditable Backend risk assessment copied from the risk decision. It does not replace the risk decision record.
- Command messages use QoS 1 by default and are not retained. A retained command could be executed unexpectedly after reconnect.

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

Backend first evaluates the Detection Event, risk state machine, device/camera-scoped cooldown, and safety conditions. Only after those checks pass does it persist a command as CREATED. MQTT does not bypass the state machine or cooldown.

For the MVP Publisher, `PUBLISHED` means dispatch was authorized and the publish attempt began; it does not prove broker delivery. Backend commits `PUBLISHED` before invoking the MQTT client so an immediate ACK cannot observe a `CREATED` row. An immediate publish failure is recorded as `FAILED`. A process crash after the commit but before or during the MQTT call can leave `PUBLISHED` without delivery; reconciliation for that crash window is a follow-up requirement.

## Scope and safety rules

- Risk and cooldown state is not global. It is managed per camera or per device according to the source of the decision and target device.
- Backend does not send GPIO numbers, PWM values, or raw actuator wiring instructions.
- Raspberry Pi maps the semantic command to local GPIO behavior and rejects expired commands before actuation.
- QoS 1 provides at-least-once delivery, so commandId deduplication is mandatory.
- Backend uses optimistic locking for concurrent command-state writers. A database optimistic-lock conflict must not trigger another MQTT publish; the handler reloads state and treats the same or already-advanced result as idempotent, while conflicting terminal state is rejected and logged.
- Commands are not retained. The retain policy for status and sensor events may be finalized with the broker deployment, but it must not change command idempotency or expiry semantics.
- ACK, status, and sensor-event timestamps include timezones; Backend stores server receipt times for audit.
- Authentication, authorization, TLS, and broker ACL configuration are deployment concerns and are not implemented in Phase 0.
