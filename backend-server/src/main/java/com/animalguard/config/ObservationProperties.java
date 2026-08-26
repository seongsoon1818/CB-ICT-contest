package com.animalguard.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "animalguard.observation")
public record ObservationProperties(
        @NotNull Duration persistenceThreshold,
        @NotNull Duration absenceGrace,
        @NotNull Duration continuityTimeout,
        @NotNull Duration soundAlertDuration,
        @NotNull Duration deterrentFullDuration
) {
    @AssertTrue(message = "all observation durations must be positive")
    public boolean isAllDurationsPositive() {
        return isPositive(persistenceThreshold)
                && isPositive(absenceGrace)
                && isPositive(continuityTimeout)
                && isPositive(soundAlertDuration)
                && isPositive(deterrentFullDuration);
    }

    @AssertTrue(message = "continuityTimeout must be greater than or equal to absenceGrace")
    public boolean isContinuityTimeoutNotShorterThanAbsenceGrace() {
        return continuityTimeout == null || absenceGrace == null
                || continuityTimeout.compareTo(absenceGrace) >= 0;
    }

    @AssertTrue(message = "command durations must fit positive integer milliseconds")
    public boolean isCommandDurationsFitIntegerMilliseconds() {
        return fitsPositiveIntegerMilliseconds(soundAlertDuration)
                && fitsPositiveIntegerMilliseconds(deterrentFullDuration);
    }

    public int soundAlertDurationMs() {
        return Math.toIntExact(soundAlertDuration.toMillis());
    }

    public int deterrentFullDurationMs() {
        return Math.toIntExact(deterrentFullDuration.toMillis());
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean fitsPositiveIntegerMilliseconds(Duration duration) {
        if (!isPositive(duration)) {
            return false;
        }
        try {
            long milliseconds = duration.toMillis();
            return milliseconds > 0 && milliseconds <= Integer.MAX_VALUE;
        } catch (ArithmeticException exception) {
            return false;
        }
    }
}
