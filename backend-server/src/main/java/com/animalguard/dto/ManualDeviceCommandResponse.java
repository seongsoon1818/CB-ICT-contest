package com.animalguard.dto;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.service.CommandDecision;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ManualDeviceCommandResponse(
        String commandId,
        CommandOutcome commandOutcome,
        List<ActuationBlocker> commandBlockers
) {
    public static ManualDeviceCommandResponse from(CommandDecision decision) {
        return new ManualDeviceCommandResponse(
                decision.commandId(),
                decision.outcome(),
                decision.blockers()
        );
    }
}
