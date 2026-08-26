package com.animalguard.service;

import com.animalguard.config.ResponsePolicyProperties;
import com.animalguard.domain.RiskLevel;
import com.animalguard.dto.DetectionEventRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseEligibilityServiceTest {

    @Test
    void disabledPolicyTreatsAcceptedDetectionsAsResponseNegative() {
        ResponseEligibilityService.ResponseEligibility result = service(
                false,
                Set.of(),
                0.0,
                null,
                null
        ).evaluate(List.of(detection("MAGPIE", 0.99, null)), assessment(RiskLevel.HIGH));

        assertThat(result.totalDetections()).isEqualTo(1);
        assertThat(result.eligibleDetections()).isZero();
        assertThat(result.responsePolicyEnabled()).isFalse();
        assertThat(result.animalPresent()).isFalse();
    }

    @Test
    void allowedClassAtThresholdIsEligible() {
        ResponseEligibilityService.ResponseEligibility result = service(
                true,
                Set.of("MAGPIE"),
                0.8,
                null,
                null
        ).evaluate(List.of(detection("MAGPIE", 0.8, null)), assessment(RiskLevel.LOW));

        assertThat(result.eligibleDetections()).isEqualTo(1);
        assertThat(result.animalPresent()).isTrue();
    }

    @Test
    void disallowedClassAndLowDetectionConfidenceAreIneligible() {
        ResponseEligibilityService service = service(
                true,
                Set.of("MAGPIE"),
                0.8,
                null,
                null
        );

        assertThat(service.evaluate(
                List.of(detection("SPARROW", 0.99, null)),
                assessment(RiskLevel.HIGH)
        ).animalPresent()).isFalse();
        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.79, null)),
                assessment(RiskLevel.HIGH)
        ).animalPresent()).isFalse();
    }

    @Test
    void unknownIsEligibleOnlyWhenExplicitlyAllowlisted() {
        DetectionEventRequest.Detection unknown = detection("UNKNOWN", 0.99, null);

        assertThat(service(true, Set.of("MAGPIE"), 0.0, null, null)
                .evaluate(List.of(unknown), assessment(RiskLevel.HIGH))
                .animalPresent()).isFalse();
        assertThat(service(true, Set.of("UNKNOWN"), 0.0, null, null)
                .evaluate(List.of(unknown), assessment(RiskLevel.HIGH))
                .animalPresent()).isTrue();
    }

    @Test
    void classificationThresholdRequiresClassifierResultAtOrAboveThreshold() {
        ResponseEligibilityService service = service(
                true,
                Set.of("MAGPIE"),
                0.0,
                0.8,
                null
        );

        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.99, null)),
                assessment(RiskLevel.HIGH)
        ).animalPresent()).isFalse();
        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.99, 0.79)),
                assessment(RiskLevel.HIGH)
        ).animalPresent()).isFalse();
        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.99, 0.8)),
                assessment(RiskLevel.HIGH)
        ).animalPresent()).isTrue();
    }

    @Test
    void minimumRiskLevelUsesExistingRiskAssessmentOrdering() {
        ResponseEligibilityService service = service(
                true,
                Set.of("MAGPIE"),
                0.0,
                null,
                RiskLevel.MEDIUM
        );

        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.99, null)),
                assessment(RiskLevel.LOW)
        ).animalPresent()).isFalse();
        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.99, null)),
                assessment(RiskLevel.MEDIUM)
        ).animalPresent()).isTrue();
        assertThat(service.evaluate(
                List.of(detection("MAGPIE", 0.99, null)),
                assessment(RiskLevel.HIGH)
        ).animalPresent()).isTrue();
    }

    @Test
    void oneEligibleDetectionMakesAggregateObservationPresent() {
        ResponseEligibilityService.ResponseEligibility result = service(
                true,
                Set.of("MAGPIE"),
                0.8,
                null,
                null
        ).evaluate(
                List.of(
                        detection("SPARROW", 0.99, null),
                        detection("MAGPIE", 0.79, null),
                        detection("MAGPIE", 0.95, null)
                ),
                assessment(RiskLevel.LOW)
        );

        assertThat(result.totalDetections()).isEqualTo(3);
        assertThat(result.eligibleDetections()).isEqualTo(1);
        assertThat(result.animalPresent()).isTrue();
    }

    private ResponseEligibilityService service(
            boolean enabled,
            Set<String> allowedClassCodes,
            double minimumDetectionConfidence,
            Double minimumClassificationConfidence,
            RiskLevel minimumRiskLevel
    ) {
        return new ResponseEligibilityService(new ResponsePolicyProperties(
                enabled,
                allowedClassCodes,
                minimumDetectionConfidence,
                minimumClassificationConfidence,
                minimumRiskLevel
        ));
    }

    private RiskDecisionEngine.RiskAssessment assessment(RiskLevel level) {
        return new RiskDecisionEngine.RiskAssessment(0, level, "test risk reason");
    }

    private DetectionEventRequest.Detection detection(
            String classCode,
            double detectionConfidence,
            Double classificationConfidence
    ) {
        return new DetectionEventRequest.Detection(
                "det-001",
                null,
                classCode,
                detectionConfidence,
                classificationConfidence,
                new DetectionEventRequest.Bbox(0, 0, 10, 10)
        );
    }
}
