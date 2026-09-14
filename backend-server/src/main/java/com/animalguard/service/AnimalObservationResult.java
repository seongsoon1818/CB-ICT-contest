package com.animalguard.service;

import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DeviceCommandType;

import java.util.Objects;

public record AnimalObservationResult(
        AnimalPresenceState presenceState,
        ObservationTrigger trigger,
        DeviceCommandType commandType,
        CommandDecision commandDecision
) {
    public AnimalObservationResult {
        Objects.requireNonNull(presenceState, "presenceState must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(commandDecision, "commandDecision must not be null");
        if (commandType == null && commandDecision.outcome() != CommandOutcome.NOT_REQUESTED) {
            throw new IllegalArgumentException("commandType is required for a requested command");
        }
        if (commandType != null && commandDecision.outcome() == CommandOutcome.NOT_REQUESTED) {
            throw new IllegalArgumentException("requested command must not produce NOT_REQUESTED");
        }
    }
}
