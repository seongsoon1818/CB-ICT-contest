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

        switch (outcome) {
            case NOT_REQUESTED -> {
                if (commandId != null || !blockers.isEmpty()) {
                    throw new IllegalArgumentException("NOT_REQUESTED must not have commandId or blockers");
                }
            }
            case CREATED -> {
                if (commandId == null || commandId.isBlank() || !blockers.isEmpty()) {
                    throw new IllegalArgumentException("CREATED requires commandId and must not have blockers");
                }
            }
            case SUPPRESSED -> {
                if (commandId != null || blockers.isEmpty()) {
                    throw new IllegalArgumentException("SUPPRESSED requires blockers and must not have commandId");
                }
            }
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
