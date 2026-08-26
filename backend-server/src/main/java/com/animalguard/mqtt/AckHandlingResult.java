package com.animalguard.mqtt;

import com.animalguard.domain.DeviceCommandStatus;

import java.util.Objects;

public record AckHandlingResult(
        AckHandlingOutcome outcome,
        DeviceCommandStatus currentStatus,
        boolean retriedAfterOptimisticLock
) {
    public AckHandlingResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    AckHandlingResult retried() {
        return new AckHandlingResult(outcome, currentStatus, true);
    }
}
