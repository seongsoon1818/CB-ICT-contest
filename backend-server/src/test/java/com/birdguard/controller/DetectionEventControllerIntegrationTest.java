package com.birdguard.controller;

import com.birdguard.domain.DeviceCommandStatus;
import com.birdguard.repository.DeviceCommandRepository;
import com.birdguard.repository.DetectionEventRepository;
import com.birdguard.repository.RiskDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DetectionEventControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DetectionEventRepository detectionEventRepository;

    @Autowired
    private RiskDecisionRepository riskDecisionRepository;

    @Autowired
    private DeviceCommandRepository deviceCommandRepository;

    @BeforeEach
    void cleanDatabase() {
        deviceCommandRepository.deleteAll();
        riskDecisionRepository.deleteAll();
        detectionEventRepository.deleteAll();
    }

    @Test
    void storesValidDetectionEventAndRiskDecision() throws Exception {
        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneBirdPayload("event-001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId", is("event-001")))
                .andExpect(jsonPath("$.riskScore", is(50)))
                .andExpect(jsonPath("$.riskLevel", is("MEDIUM")))
                .andExpect(jsonPath("$.commandId").doesNotExist());

        org.assertj.core.api.Assertions.assertThat(detectionEventRepository.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(riskDecisionRepository.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(deviceCommandRepository.count()).isZero();
    }

    @Test
    void createsDeviceCommandWhenRiskIsHigh() throws Exception {
        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(highRiskPayload("event-high")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(70)))
                .andExpect(jsonPath("$.riskLevel", is("HIGH")))
                .andExpect(jsonPath("$.commandId").isString());

        org.assertj.core.api.Assertions.assertThat(deviceCommandRepository.count()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(deviceCommandRepository.findAll().get(0).getStatus())
                .isEqualTo(DeviceCommandStatus.CREATED);
    }

    @Test
    void rejectsConfidenceAboveOne() throws Exception {
        String payload = oneBirdPayload("event-invalid-confidence")
                .replace("\"detectionConfidence\": 0.95", "\"detectionConfidence\": 1.01");

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNegativeBoundingBox() throws Exception {
        String payload = oneBirdPayload("event-invalid-bbox")
                .replace("\"x\": 100", "\"x\": -1");

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingEventId() throws Exception {
        String payload = oneBirdPayload("event-missing-id")
                .replace("\"eventId\": \"event-missing-id\"", "\"eventId\": \"\"");

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNullBirdEntry() throws Exception {
        String payload = oneBirdPayload("event-null-bird")
                .replace("\"birds\": [", "\"birds\": [null,");

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateEventIdWithConflict() throws Exception {
        String payload = oneBirdPayload("event-duplicate");

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict());

        org.assertj.core.api.Assertions.assertThat(detectionEventRepository.count()).isEqualTo(1);
    }

    private String oneBirdPayload(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "2026-08-21T18:00:00Z",
                  "image": {"width": 1920, "height": 1080},
                  "birds": [
                    {
                      "detectionId": "det-001",
                      "trackId": null,
                      "speciesCode": "MAGPIE",
                      "detectionConfidence": 0.95,
                      "classificationConfidence": 0.92,
                      "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}
                    }
                  ]
                }
                """.formatted(eventId);
    }

    private String highRiskPayload(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "2026-08-21T18:00:00Z",
                  "image": {"width": 1920, "height": 1080},
                  "birds": [
                    {"detectionId": "det-001", "trackId": null, "speciesCode": "MAGPIE", "detectionConfidence": 0.95, "classificationConfidence": 0.92, "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}},
                    {"detectionId": "det-002", "trackId": null, "speciesCode": "MAGPIE", "detectionConfidence": 0.80, "classificationConfidence": 0.80, "bbox": {"x": 200, "y": 300, "width": 50, "height": 60}},
                    {"detectionId": "det-003", "trackId": null, "speciesCode": "SPARROW", "detectionConfidence": 0.70, "classificationConfidence": 0.70, "bbox": {"x": 300, "y": 400, "width": 50, "height": 60}}
                  ]
                }
                """.formatted(eventId);
    }
}
