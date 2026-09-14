package com.animalguard.mqtt;

import java.util.Arrays;

public record PreparedMqttCommand(
        String commandId,
        String topic,
        byte[] payload
) {
    public PreparedMqttCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be blank");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("payload must not be empty");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
