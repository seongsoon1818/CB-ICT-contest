package com.animalguard.mqtt;

import com.animalguard.domain.DeviceOperationalStatus;

import java.time.Instant;

public record MqttDeviceStatusMessage(
        String deviceId,
        DeviceOperationalStatus status,
        Instant reportedAt,
        String firmwareVersion
) {
}
