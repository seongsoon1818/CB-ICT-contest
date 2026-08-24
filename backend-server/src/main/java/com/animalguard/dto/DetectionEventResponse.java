package com.animalguard.dto;

import com.animalguard.domain.RiskLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionEventResponse(
        String eventId,
        int riskScore,
        RiskLevel riskLevel,
        String commandId
) {
}
