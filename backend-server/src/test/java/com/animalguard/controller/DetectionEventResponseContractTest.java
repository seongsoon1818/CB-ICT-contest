package com.animalguard.controller;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DetectionEventResponseContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesPublishedCommandOutcomeValues() throws JsonProcessingException {
        assertThat(objectMapper.writeValueAsString(CommandOutcome.values()))
                .isEqualTo("[\"NOT_REQUESTED\",\"CREATED\",\"SUPPRESSED\"]");
    }

    @Test
    void preservesPublishedActuationBlockerValues() throws JsonProcessingException {
        assertThat(objectMapper.writeValueAsString(ActuationBlocker.values()))
                .isEqualTo("[\"ACTUATION_DISABLED\",\"RISK_POLICY_UNCONFIRMED\","
                        + "\"CAMERA_DEVICE_MAPPING_EMPTY\",\"MQTT_PUBLISHER_NOT_READY\","
                        + "\"CAMERA_UNMAPPED\",\"COOLDOWN_ACTIVE\","
                        + "\"AUTOMATIC_RETRY_EXHAUSTED\",\"OPERATOR_API_DISABLED\","
                        + "\"RESPONSE_POLICY_DISABLED\"]");
    }
}
