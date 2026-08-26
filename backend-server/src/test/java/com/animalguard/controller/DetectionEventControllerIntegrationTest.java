package com.animalguard.controller;

import com.animalguard.repository.AnimalDetectionRepository;
import com.animalguard.repository.AnimalObservationStateRepository;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.repository.DetectionEventRepository;
import com.animalguard.repository.RiskDecisionRepository;
import com.animalguard.service.ActuationTransportReadiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static java.util.stream.Collectors.joining;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DetectionEventControllerIntegrationTest.TestClockConfiguration.class)
class DetectionEventControllerIntegrationTest {

    private static final String EVENT_ID = "15356786-9588-4db4-a0fe-f8acd6300868";
    private static final Instant TEST_NOW = Instant.parse("2026-08-25T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DetectionEventRepository detectionEventRepository;

    @Autowired
    private AnimalDetectionRepository animalDetectionRepository;

    @Autowired
    private RiskDecisionRepository riskDecisionRepository;

    @Autowired
    private DeviceCommandRepository deviceCommandRepository;

    @Autowired
    private AnimalObservationStateRepository observationStateRepository;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MutableActuationTransportReadiness transportReadiness;

    @BeforeEach
    void cleanDatabase() {
        clock.set(TEST_NOW);
        transportReadiness.setReady(true);
        deviceCommandRepository.deleteAll();
        observationStateRepository.deleteAll();
        riskDecisionRepository.deleteAll();
        detectionEventRepository.deleteAll();
    }

    @Test
    void storesValidDetectionEventDetectionsModelAndRiskDecision() throws Exception {
        transportReadiness.setReady(false);

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneDetectionPayload(EVENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId", is(EVENT_ID)))
                .andExpect(jsonPath("$.riskScore", is(45)))
                .andExpect(jsonPath("$.riskLevel", is("MEDIUM")))
                .andExpect(jsonPath("$.commandOutcome", is("SUPPRESSED")))
                .andExpect(jsonPath("$.commandBlockers", hasItem("MQTT_PUBLISHER_NOT_READY")))
                .andExpect(jsonPath("$.commandId").doesNotExist());

        assertThat(detectionEventRepository.count()).isEqualTo(1);
        assertThat(animalDetectionRepository.count()).isEqualTo(1);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(deviceCommandRepository.count()).isZero();
        assertThat(detectionEventRepository.findAll().get(0).getDetectorVersion())
                .isEqualTo("animal-detector-v1");
        assertThat(detectionEventRepository.findAll().get(0).getClassifierVersion()).isNull();
        assertThat(animalDetectionRepository.findAll().get(0).getClassCode()).isEqualTo("MAGPIE");
        assertThat(animalDetectionRepository.findAll().get(0).getClassificationConfidence()).isNull();
    }

    @Test
    void selectsFirstDetectionSoundAlertRegardlessOfRiskLevel() throws Exception {
        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(highRiskPayload(EVENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(70)))
                .andExpect(jsonPath("$.riskLevel", is("HIGH")))
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")))
                .andExpect(jsonPath("$.commandBlockers").isEmpty())
                .andExpect(jsonPath("$.commandId").isNotEmpty());

        assertThat(detectionEventRepository.count()).isEqualTo(1);
        assertThat(animalDetectionRepository.count()).isEqualTo(3);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(deviceCommandRepository.count()).isEqualTo(1);
    }

    @Test
    void selectsFirstDetectionSoundAlertForAcceptedLowConfidenceUnknownDetection() throws Exception {
        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lowConfidenceUnknownPayload(EVENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(0)))
                .andExpect(jsonPath("$.riskLevel", is("LOW")))
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")))
                .andExpect(jsonPath("$.commandBlockers").isEmpty())
                .andExpect(jsonPath("$.commandId").isNotEmpty());

        assertThat(detectionEventRepository.count()).isEqualTo(1);
        assertThat(animalDetectionRepository.count()).isEqualTo(1);
        assertThat(riskDecisionRepository.count()).isEqualTo(1);
        assertThat(deviceCommandRepository.count()).isEqualTo(1);
    }

    @Test
    void acceptsEmptyDetectionsAsLowRiskWithoutDeviceCommand() throws Exception {
        transportReadiness.setReady(false);

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyDetectionsPayload(EVENT_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.riskScore", is(0)))
                .andExpect(jsonPath("$.riskLevel", is("LOW")))
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")))
                .andExpect(jsonPath("$.commandBlockers").isEmpty())
                .andExpect(jsonPath("$.commandId").doesNotExist());

        assertThat(detectionEventRepository.count()).isEqualTo(1);
        assertThat(animalDetectionRepository.count()).isZero();
        assertThat(deviceCommandRepository.count()).isZero();
    }

    @Test
    void storesRiskDecisionButDoesNotChangeObservationForStaleCapturedAt() throws Exception {
        String currentEventId = "15356786-9588-4db4-a0fe-f8acd6300869";
        String staleEventId = "15356786-9588-4db4-a0fe-f8acd6300870";

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneDetectionPayloadAt(currentEventId, "2026-08-24T08:00:01Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandOutcome", is("CREATED")));

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyDetectionsPayloadAt(staleEventId, "2026-08-24T08:00:00Z")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.commandOutcome", is("NOT_REQUESTED")));

        assertThat(detectionEventRepository.count()).isEqualTo(2);
        assertThat(riskDecisionRepository.count()).isEqualTo(2);
        assertThat(deviceCommandRepository.count()).isEqualTo(1);
        assertThat(observationStateRepository.findByCameraId("cam-001").orElseThrow()
                .getLastProcessedCapturedAt()).isEqualTo(Instant.parse("2026-08-24T08:00:01Z"));
    }

    @Test
    void rejectsDuplicateEventIdWithConflict() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID);

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isConflict());

        assertThat(detectionEventRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsMissingModel() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"model\": {\"detectorVersion\": \"animal-detector-v1\", \"classifierVersion\": null},", "");

        assertBadRequest(payload);
    }

    @Test
    void rejectsMissingDetectorVersion() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("{\"detectorVersion\": \"animal-detector-v1\", \"classifierVersion\": null}",
                        "{\"classifierVersion\": null}");

        assertBadRequest(payload);
    }

    @Test
    void rejectsMissingClassifierVersionKey() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace(", \"classifierVersion\": null", "");

        assertBadRequest(payload);
    }

    @Test
    void rejectsMissingClassificationConfidenceKey() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"classificationConfidence\": null,", "");

        assertBadRequest(payload);
    }

    @Test
    void rejectsNullClassificationConfidenceWhenClassifierExists() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"classifierVersion\": null", "\"classifierVersion\": \"animal-classifier-v1\"");

        assertBadRequest(payload);
    }

    @Test
    void rejectsClassificationConfidenceWhenClassifierIsAbsent() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"classificationConfidence\": null", "\"classificationConfidence\": 0.92");

        assertBadRequest(payload);
    }

    @Test
    void rejectsMissingDetections() throws Exception {
        assertBadRequest(payloadWithoutDetections(EVENT_ID));
    }

    @Test
    void rejectsLegacyBirdsField() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"detections\": [", "\"birds\": [],\n  \"detections\": [");

        assertBadRequest(payload);
    }

    @Test
    void rejectsMissingClassCode() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"classCode\": \"MAGPIE\",", "");

        assertBadRequest(payload);
    }

    @Test
    void rejectsMissingTrackIdKey() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"trackId\": null,", "");

        assertBadRequest(payload);
    }

    @Test
    void rejectsInvalidClassCodeFormat() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"classCode\": \"MAGPIE\"", "\"classCode\": \"wild-boar\"");

        assertBadRequest(payload);
    }

    @Test
    void rejectsConfidenceOutsideZeroToOne() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"detectionConfidence\": 0.95", "\"detectionConfidence\": 1.01");

        assertBadRequest(payload);
    }

    @Test
    void rejectsNegativeBoundingBoxCoordinate() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID).replace("\"x\": 100", "\"x\": -1");

        assertBadRequest(payload);
    }

    @Test
    void rejectsZeroBoundingBoxSize() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID).replace("\"width\": 50", "\"width\": 0");

        assertBadRequest(payload);
    }

    @Test
    void rejectsBoundingBoxOutsideImage() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"x\": 100", "\"x\": 1250");

        assertBadRequest(payload);
    }

    @Test
    void rejectsNonUuidEventId() throws Exception {
        assertBadRequest(oneDetectionPayload("event-not-uuid"));
    }

    @Test
    void rejectsNullDetectionEntry() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"detections\": [", "\"detections\": [null,");

        assertBadRequest(payload);
    }

    @Test
    void validationErrorIncludesViolationDetails() throws Exception {
        String payload = oneDetectionPayload(EVENT_ID)
                .replace("\"classCode\": \"MAGPIE\"", "\"classCode\": \"wild-boar\"");

        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.violations[*].field", hasItem("detections[0].classCode")))
                .andExpect(jsonPath("$.violations[0].message").isNotEmpty());
    }

    @Test
    void rejectsDuplicateDetectionIdsWithinEvent() throws Exception {
        assertBadRequest(payloadWithDetectionIds(EVENT_ID, "det-001", "det-001"));

        assertThat(detectionEventRepository.count()).isZero();
        assertThat(animalDetectionRepository.count()).isZero();
        assertThat(riskDecisionRepository.count()).isZero();
        assertThat(deviceCommandRepository.count()).isZero();
    }

    @Test
    void rejectsMoreThanOneHundredDetections() throws Exception {
        String[] detectionIds = IntStream.rangeClosed(1, 101)
                .mapToObj(index -> "det-%03d".formatted(index))
                .toArray(String[]::new);

        assertBadRequest(payloadWithDetectionIds(EVENT_ID, detectionIds));

        assertThat(detectionEventRepository.count()).isZero();
        assertThat(animalDetectionRepository.count()).isZero();
        assertThat(riskDecisionRepository.count()).isZero();
        assertThat(deviceCommandRepository.count()).isZero();
    }

    private void assertBadRequest(String payload) throws Exception {
        mockMvc.perform(post("/api/v1/detection/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest());
    }

    private String oneDetectionPayload(String eventId) {
        return oneDetectionPayloadAt(eventId, "2026-08-24T08:00:00Z");
    }

    private String oneDetectionPayloadAt(String eventId, String capturedAt) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "%s",
                  "image": {"width": 1280, "height": 720},
                  "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null},
                  "detections": [
                    {
                      "detectionId": "det-001",
                      "trackId": null,
                      "classCode": "MAGPIE",
                      "detectionConfidence": 0.95,
                      "classificationConfidence": null,
                      "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}
                    }
                  ]
                }
                """.formatted(eventId, capturedAt);
    }

    private String highRiskPayload(String eventId) {
        return highRiskPayload(eventId, "cam-001");
    }

    private String lowConfidenceUnknownPayload(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "2026-08-24T08:00:00Z",
                  "image": {"width": 1280, "height": 720},
                  "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null},
                  "detections": [
                    {"detectionId": "det-001", "trackId": null, "classCode": "UNKNOWN", "detectionConfidence": 0.01, "classificationConfidence": null, "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}}
                  ]
                }
                """.formatted(eventId);
    }

    private String highRiskPayload(String eventId, String cameraId) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "%s",
                  "capturedAt": "2026-08-24T08:00:00Z",
                  "image": {"width": 1280, "height": 720},
                  "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": "animal-classifier-v1"},
                  "detections": [
                    {"detectionId": "det-001", "trackId": null, "classCode": "MAGPIE", "detectionConfidence": 0.95, "classificationConfidence": 0.92, "bbox": {"x": 100, "y": 200, "width": 50, "height": 60}},
                    {"detectionId": "det-002", "trackId": null, "classCode": "SPARROW", "detectionConfidence": 0.80, "classificationConfidence": 0.80, "bbox": {"x": 200, "y": 300, "width": 50, "height": 60}},
                    {"detectionId": "det-003", "trackId": null, "classCode": "WILD_BOAR", "detectionConfidence": 0.70, "classificationConfidence": 0.70, "bbox": {"x": 300, "y": 400, "width": 50, "height": 60}}
                  ]
                }
                """.formatted(eventId, cameraId);
    }

    private String emptyDetectionsPayload(String eventId) {
        return emptyDetectionsPayloadAt(eventId, "2026-08-24T08:00:00Z");
    }

    private String emptyDetectionsPayloadAt(String eventId, String capturedAt) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "%s",
                  "image": {"width": 1280, "height": 720},
                  "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null},
                  "detections": []
                }
                """.formatted(eventId, capturedAt);
    }

    private String payloadWithoutDetections(String eventId) {
        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "2026-08-24T08:00:00Z",
                  "image": {"width": 1280, "height": 720},
                  "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null}
                }
                """.formatted(eventId);
    }

    private String payloadWithDetectionIds(String eventId, String... detectionIds) {
        String detections = Arrays.stream(detectionIds)
                .map(detectionId -> """
                        {
                          "detectionId": "%s",
                          "trackId": null,
                          "classCode": "UNKNOWN",
                          "detectionConfidence": 0.50,
                          "classificationConfidence": null,
                          "bbox": {"x": 10, "y": 20, "width": 30, "height": 40}
                        }
                        """.formatted(detectionId))
                .collect(joining(","));

        return """
                {
                  "eventId": "%s",
                  "cameraId": "cam-001",
                  "capturedAt": "2026-08-24T08:00:00Z",
                  "image": {"width": 1280, "height": 720},
                  "model": {"detectorVersion": "animal-detector-v1", "classifierVersion": null},
                  "detections": [%s]
                }
                """.formatted(eventId, detections);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(TEST_NOW);
        }

        @Bean
        MutableActuationTransportReadiness mutableActuationTransportReadiness() {
            return new MutableActuationTransportReadiness();
        }
    }

    static final class MutableActuationTransportReadiness implements ActuationTransportReadiness {

        private boolean ready;

        void setReady(boolean ready) {
            this.ready = ready;
        }

        @Override
        public boolean isReady() {
            return ready;
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current.get(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
