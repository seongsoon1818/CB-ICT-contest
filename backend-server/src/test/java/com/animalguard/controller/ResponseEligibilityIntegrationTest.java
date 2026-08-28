package com.animalguard.controller;

import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.repository.AnimalDetectionRepository;
import com.animalguard.repository.AnimalObservationStateRepository;
import com.animalguard.repository.DetectionEventRepository;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.repository.RiskDecisionRepository;
import com.animalguard.service.ActuationTransportReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=true",
        "animalguard.response-policy.enabled=true",
        "animalguard.response-policy.allowed-class-codes=MAGPIE,UNKNOWN",
        "animalguard.response-policy.minimum-detection-confidence=0.8"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ResponseEligibilityIntegrationTest.TestBeans.class)
@ExtendWith(OutputCaptureExtension.class)
class ResponseEligibilityIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-08-26T07:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DetectionEventRepository eventRepository;
    @Autowired
    private AnimalDetectionRepository detectionRepository;
    @Autowired
    private RiskDecisionRepository riskDecisionRepository;
    @Autowired
    private AnimalObservationStateRepository observationRepository;
    @Autowired
    private DeviceCommandRepository commandRepository;

    @BeforeEach
    void cleanDatabase() {
        commandRepository.deleteAll();
        observationRepository.deleteAll();
        riskDecisionRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void keepsAuditButTreatsDisallowedDetectionAsResponseNegative(CapturedOutput output) throws Exception {
        String eventId = "15356786-9588-4db4-a0fe-f8acd6300901";

        perform(eventId, "det-ineligible", "SPARROW", 0.99, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(20)))
                .andExpect(jsonPath("$.riskLevel", is("LOW")))
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")))
                .andExpect(jsonPath("$.commandBlockers").isEmpty());

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(detectionRepository.count()).isEqualTo(1);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(commandRepository.count()).isZero();
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
        assertThat(output)
                .contains("totalDetections=1")
                .contains("eligibleDetections=0")
                .contains("responsePolicyEnabled=true")
                .contains("minimumRiskLevel=HIGH")
                .contains("animalPresent=false")
                .doesNotContain("det-ineligible")
                .doesNotContain("\"bbox\"");
    }

    @Test
    void allowedLowRiskDetectionRemainsAuditableWithoutStartingObservation() throws Exception {
        perform("15356786-9588-4db4-a0fe-f8acd6300902", "det-low-risk", "UNKNOWN", 0.99, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(20)))
                .andExpect(jsonPath("$.riskLevel", is("LOW")))
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")));

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(detectionRepository.count()).isEqualTo(1);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(commandRepository.count()).isZero();
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
    }

    @Test
    void allowedMediumRiskDetectionRemainsAuditableWithoutStartingObservation() throws Exception {
        perform("15356786-9588-4db4-a0fe-f8acd6300903", "det-medium-risk", "MAGPIE", 0.95, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(45)))
                .andExpect(jsonPath("$.riskLevel", is("MEDIUM")))
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")));

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(detectionRepository.count()).isEqualTo(1);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(commandRepository.count()).isZero();
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
    }

    @Test
    void allowedHighRiskDetectionStartsObservationAndLowRiskDetectionsEndIt() throws Exception {
        performHighRisk("15356786-9588-4db4-a0fe-f8acd6300904", 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(70)))
                .andExpect(jsonPath("$.riskLevel", is("HIGH")))
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")));

        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.PRESENT);

        perform("15356786-9588-4db4-a0fe-f8acd6300905", "det-miss-1", "UNKNOWN", 0.99, 1)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")));
        perform("15356786-9588-4db4-a0fe-f8acd6300906", "det-miss-2", "UNKNOWN", 0.99, 3)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")));

        assertThat(eventRepository.count()).isEqualTo(3);
        assertThat(riskDecisionRepository.count()).isEqualTo(3);
        assertThat(commandRepository.count()).isEqualTo(1);
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
    }

    @Test
    void lowDetectionConfidenceRemainsAuditableWithoutStartingObservation() throws Exception {
        perform("15356786-9588-4db4-a0fe-f8acd6300907", "det-low-confidence", "MAGPIE", 0.79, 0)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(25)))
                .andExpect(jsonPath("$.riskLevel", is("LOW")))
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")));

        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(commandRepository.count()).isZero();
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            String eventId,
            String detectionId,
            String classCode,
            double detectionConfidence,
            long capturedAtOffsetSeconds
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/detection/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "eventId": "%s",
                          "cameraId": "cam-001",
                          "capturedAt": "%s",
                          "image": {"width": 1280, "height": 720},
                          "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null},
                          "detections": [{
                            "detectionId": "%s",
                            "trackId": null,
                            "classCode": "%s",
                            "detectionConfidence": %s,
                            "classificationConfidence": null,
                            "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}
                          }]
                        }
                        """.formatted(
                        eventId,
                        BASE.plusSeconds(capturedAtOffsetSeconds),
                        detectionId,
                        classCode,
                        detectionConfidence
                )));
    }

    private org.springframework.test.web.servlet.ResultActions performHighRisk(
            String eventId,
            long capturedAtOffsetSeconds
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/detection/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "eventId": "%s",
                          "cameraId": "cam-001",
                          "capturedAt": "%s",
                          "image": {"width": 1280, "height": 720},
                          "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null},
                          "detections": [
                            {
                              "detectionId": "det-high-1",
                              "trackId": null,
                              "classCode": "MAGPIE",
                              "detectionConfidence": 0.95,
                              "classificationConfidence": null,
                              "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}
                            },
                            {
                              "detectionId": "det-high-2",
                              "trackId": null,
                              "classCode": "MAGPIE",
                              "detectionConfidence": 0.95,
                              "classificationConfidence": null,
                              "bbox": {"x": 200, "y": 200, "width": 50, "height": 60}
                            },
                            {
                              "detectionId": "det-high-3",
                              "trackId": null,
                              "classCode": "MAGPIE",
                              "detectionConfidence": 0.95,
                              "classificationConfidence": null,
                              "bbox": {"x": 300, "y": 200, "width": 50, "height": 60}
                            }
                          ]
                        }
                        """.formatted(eventId, BASE.plusSeconds(capturedAtOffsetSeconds))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        Clock responsePolicyClock() {
            return Clock.fixed(BASE, ZoneOffset.UTC);
        }

        @Bean
        ActuationTransportReadiness readyTransport() {
            return () -> true;
        }
    }
}
