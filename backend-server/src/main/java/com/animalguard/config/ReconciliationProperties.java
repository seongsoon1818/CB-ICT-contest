package com.animalguard.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "animalguard.reconciliation")
public record ReconciliationProperties(
        @NotNull Duration scanInterval,
        @NotNull Duration publishedTimeout,
        @NotNull Duration acknowledgedTimeout,
        @Positive int maxAutomaticAttemptsPerSession
) {
    @AssertTrue(message = "reconciliation durations must be positive")
    public boolean isEveryDurationPositive() {
        return isPositive(scanInterval)
                && isPositive(publishedTimeout)
                && isPositive(acknowledgedTimeout);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
