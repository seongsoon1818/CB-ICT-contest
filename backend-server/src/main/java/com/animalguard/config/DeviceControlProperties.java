package com.animalguard.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "animalguard.device-control")
public record DeviceControlProperties(
        @NotNull Duration cooldown,
        Map<
                @NotBlank @Size(max = 64)
                @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$") String,
                @NotBlank @Size(max = 100) String
                > cameraDeviceMappings
) {
    public DeviceControlProperties {
        cameraDeviceMappings = cameraDeviceMappings == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(cameraDeviceMappings));
    }

    @AssertTrue(message = "cooldown must be positive")
    public boolean isCooldownPositive() {
        return cooldown == null || (!cooldown.isZero() && !cooldown.isNegative());
    }
}
