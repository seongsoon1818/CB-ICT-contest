package com.animalguard.mqtt;

import java.time.Instant;

public record MqttAckMessage(
        String commandId,
        String deviceId,
        MqttAckStatus status,
        Instant reportedAt
) {
}
