package com.animalguard.mqtt;

import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({
        "commandId",
        "eventId",
        "deviceId",
        "source",
        "command",
        "durationMs",
        "issuedAt",
        "expiresAt",
        "reason"
})
public record MqttCommandPayload(
        String commandId,
        UUID eventId,
        String deviceId,
        DeviceCommandSource source,
        DeviceCommandType command,
        Integer durationMs,
        Instant issuedAt,
        Instant expiresAt,
        String reason
) {

    public static MqttCommandPayload from(DeviceCommand deviceCommand) {
        Objects.requireNonNull(deviceCommand, "deviceCommand must not be null");
        DeviceCommandSource source = Objects.requireNonNull(
                deviceCommand.getSource(),
                "deviceCommand source must not be null"
        );
        DeviceCommandType command = Objects.requireNonNull(
                deviceCommand.getCommandType(),
                "deviceCommand commandType must not be null"
        );
        DetectionEvent event = deviceCommand.getEvent();
        UUID eventId = eventId(source, event);
        validateSourceCommand(source, command);
        validateDuration(command, deviceCommand.getDurationMs());

        Instant issuedAt = Objects.requireNonNull(deviceCommand.getIssuedAt(), "issuedAt must not be null");
        Instant expiresAt = Objects.requireNonNull(deviceCommand.getExpiresAt(), "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new MqttPayloadContractException("expiresAt must be after issuedAt");
        }

        return new MqttCommandPayload(
                requireText(deviceCommand.getCommandId(), "commandId"),
                eventId,
                requireText(deviceCommand.getDeviceId(), "deviceId"),
                source,
                command,
                deviceCommand.getDurationMs(),
                issuedAt,
                expiresAt,
                requireText(deviceCommand.getReason(), "reason")
        );
    }

    private static UUID eventId(DeviceCommandSource source, DetectionEvent event) {
        if (source == DeviceCommandSource.MANUAL) {
            if (event != null) {
                throw new MqttPayloadContractException("eventId must be null for MANUAL commands");
            }
            return null;
        }
        if (event == null) {
            throw new MqttPayloadContractException("eventId is required for AUTOMATIC commands");
        }
        try {
            return UUID.fromString(event.getEventId());
        } catch (IllegalArgumentException exception) {
            throw new MqttPayloadContractException("eventId must be a UUID", exception);
        }
    }

    private static void validateSourceCommand(DeviceCommandSource source, DeviceCommandType command) {
        boolean allowed = switch (source) {
            case AUTOMATIC -> switch (command) {
                case SOUND_ALERT, DETERRENT_FULL, STOP_DETERRENT -> true;
                case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT -> false;
            };
            case MANUAL -> switch (command) {
                case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT, STOP_DETERRENT -> true;
                case SOUND_ALERT, DETERRENT_FULL -> false;
            };
        };
        if (!allowed) {
            throw new MqttPayloadContractException(
                    "command " + command + " is not allowed for source " + source
            );
        }
    }

    private static void validateDuration(DeviceCommandType command, Integer durationMs) {
        if (command == DeviceCommandType.SOUND_ALERT || command == DeviceCommandType.DETERRENT_FULL) {
            if (durationMs == null || durationMs <= 0) {
                throw new MqttPayloadContractException("durationMs must be positive for " + command);
            }
        } else if (durationMs != null) {
            throw new MqttPayloadContractException("durationMs must be null for " + command);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new MqttPayloadContractException(field + " must not be blank");
        }
        return value;
    }
}
