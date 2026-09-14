package com.animalguard.dto;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.RiskLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionEventResponse(
        String eventId,
        int riskScore,
        RiskLevel riskLevel,
        String commandId,
        CommandOutcome commandOutcome,
        List<ActuationBlocker> commandBlockers
) {
}
