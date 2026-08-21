# Raspberry Pi MQTT v1 Contract

## Scope

This document defines the Backend-to-device command boundary and the Raspberry Pi acknowledgement, status, and sensor-event topics. Backend sends semantic commands; Raspberry Pi owns the mapping from a semantic command to GPIO actions.

Backend never sends GPIO pin numbers.

## Topics

The topic prefix is birdguard.

| Topic | Direction | Purpose | Default delivery |
| --- | --- | --- | --- |
| birdguard/devices/{deviceId}/commands | Backend → Raspberry Pi | Semantic device commands | QoS 1, retain false |
| birdguard/devices/{deviceId}/acks | Raspberry Pi → Backend | Command processing result | QoS 1 |
| birdguard/devices/{deviceId}/status | Raspberry Pi → Backend | Device health and state | QoS 1 |
| birdguard/devices/{deviceId}/sensor-events | Raspberry Pi → Backend | Sensor observations | QoS 1 |

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
  "reason": "HIGH_RISK_MAGPIE"
}
~~~

Rules:

- commandId is a unique, durable idempotency key. The same commandId must not cause a second physical actuation.
- eventId is the Detection Event UUID that caused the command.
- command is semantic, such as DETERRENT_LEVEL_1, DETERRENT_LEVEL_2, DETERRENT_LEVEL_3, or STOP_DETERRENT. It is not a GPIO instruction.
- durationMs is a positive duration in milliseconds.
- issuedAt and expiresAt use ISO 8601 / RFC 3339 date-time with an explicit timezone.
- expiresAt must be later than issuedAt. Raspberry Pi must not execute a command after expiresAt.
- reason is a short, auditable Backend reason and does not replace the risk decision record.
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

The ACK status is one of ACKNOWLEDGED, EXECUTED, FAILED, or EXPIRED. A device publishes an ACK after processing a command. If a duplicate commandId is received, the device does not actuate again; it may publish the previously recorded ACK again.

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

Backend first evaluates the Detection Event, risk state machine, device/camera-scoped cooldown, and safety conditions. Only after those checks pass does it persist a command as CREATED and publish it. MQTT does not bypass the state machine or cooldown.

## Scope and safety rules

- Risk and cooldown state is not global. It is managed per camera or per device according to the source of the decision and target device.
- Backend does not send GPIO numbers, PWM values, or raw actuator wiring instructions.
- Raspberry Pi maps the semantic command to local GPIO behavior and rejects expired commands before actuation.
- QoS 1 provides at-least-once delivery, so commandId deduplication is mandatory.
- Commands are not retained. The retain policy for status and sensor events may be finalized with the broker deployment, but it must not change command idempotency or expiry semantics.
- ACK, status, and sensor-event timestamps include timezones; Backend stores server receipt times for audit.
- Authentication, authorization, TLS, and broker ACL configuration are deployment concerns and are not implemented in Phase 0.
