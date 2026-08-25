package com.animalguard.service;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;

import java.util.List;
import java.util.Objects;

public record CommandDecision(
        CommandOutcome outcome,
        String commandId,
        List<ActuationBlocker> blockers
) {
    public CommandDecision {
        Objects.requireNonNull(outcome, "outcome must not be null");
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers must not be null"));

        String validationError = switch (outcome) {
            case NOT_REQUESTED -> commandId == null && blockers.isEmpty()
                    ? null
                    : "NOT_REQUESTED must not have commandId or blockers";
            case CREATED -> commandId != null && !commandId.isBlank() && blockers.isEmpty()
                    ? null
                    : "CREATED requires commandId and must not have blockers";
            case SUPPRESSED -> commandId == null && !blockers.isEmpty()
                    ? null
                    : "SUPPRESSED requires blockers and must not have commandId";
        };
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
    }

    public static CommandDecision notRequested() {
        return new CommandDecision(CommandOutcome.NOT_REQUESTED, null, List.of());
    }

    public static CommandDecision created(String commandId) {
        return new CommandDecision(CommandOutcome.CREATED, commandId, List.of());
    }

    public static CommandDecision suppressed(List<ActuationBlocker> blockers) {
        return new CommandDecision(CommandOutcome.SUPPRESSED, null, blockers);
    }
}
