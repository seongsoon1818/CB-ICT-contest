package com.birdguard.dto;

import com.birdguard.domain.RiskLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionEventResponse(
        String eventId,
        int riskScore,
        RiskLevel riskLevel,
        String commandId
) {
}
