package com.animalguard.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "animalguard.operator-api")
public record OperatorApiProperties(
        boolean enabled,
        String token
) {
    public OperatorApiProperties {
        token = token == null ? "" : token;
    }

    @AssertTrue(message = "token must not be blank when operator API is enabled")
    public boolean isTokenConfiguredWhenEnabled() {
        return !enabled || !token.isBlank();
    }

    @Override
    public String toString() {
        return "OperatorApiProperties[enabled=" + enabled + ", token=<redacted>]";
    }
}
