package com.animalguard.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "animalguard.mqtt")
public record MqttProperties(
        boolean enabled,
        @NotBlank String host,
        @Min(1) @Max(65_535) int port,
        @NotBlank String clientId,
        String username,
        String password,
        @NotNull Duration connectTimeout,
        @NotNull Duration publishTimeout,
        @NotNull Duration dispatchInterval,
        @Positive int dispatchBatchSize
) {
    public MqttProperties {
        username = username == null ? "" : username;
        password = password == null ? "" : password;
    }

    @AssertTrue(message = "connectTimeout must be positive")
    public boolean isConnectTimeoutPositive() {
        return isPositive(connectTimeout);
    }

    @AssertTrue(message = "publishTimeout must be positive")
    public boolean isPublishTimeoutPositive() {
        return isPositive(publishTimeout);
    }

    @AssertTrue(message = "dispatchInterval must be positive")
    public boolean isDispatchIntervalPositive() {
        return isPositive(dispatchInterval);
    }

    private static boolean isPositive(Duration duration) {
        return duration == null || (!duration.isZero() && !duration.isNegative());
    }

    @Override
    public String toString() {
        return "MqttProperties[enabled=" + enabled
                + ", host=" + host
                + ", port=" + port
                + ", clientId=" + clientId
                + ", username=" + username
                + ", password=<redacted>"
                + ", connectTimeout=" + connectTimeout
                + ", publishTimeout=" + publishTimeout
                + ", dispatchInterval=" + dispatchInterval
                + ", dispatchBatchSize=" + dispatchBatchSize
                + "]";
    }
}
