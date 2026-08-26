package com.animalguard.service;

import com.animalguard.config.ResponsePolicyProperties;
import com.animalguard.domain.RiskLevel;
import com.animalguard.dto.DetectionEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ResponseEligibilityService {

    private final ResponsePolicyProperties properties;

    public ResponseEligibility evaluate(
            List<DetectionEventRequest.Detection> detections,
            RiskDecisionEngine.RiskAssessment assessment
    ) {
        Objects.requireNonNull(detections, "detections must not be null");
        Objects.requireNonNull(assessment, "assessment must not be null");

        int eligibleDetections = 0;
        if (properties.enabled() && meetsMinimumRiskLevel(assessment.level())) {
            eligibleDetections = (int) detections.stream()
                    .filter(this::isEligibleDetection)
                    .count();
        }

        return new ResponseEligibility(
                detections.size(),
                eligibleDetections,
                properties.enabled(),
                properties.minimumRiskLevel(),
                eligibleDetections > 0
        );
    }

    private boolean isEligibleDetection(DetectionEventRequest.Detection detection) {
        if (!properties.allowedClassCodes().contains(detection.classCode())
                || detection.detectionConfidence() < properties.minimumDetectionConfidence()) {
            return false;
        }

        Double threshold = properties.minimumClassificationConfidence();
        return threshold == null
                || detection.classificationConfidence() != null
                && detection.classificationConfidence() >= threshold;
    }

    private boolean meetsMinimumRiskLevel(RiskLevel actual) {
        RiskLevel minimum = properties.minimumRiskLevel();
        return minimum == null || actual.compareTo(minimum) >= 0;
    }

    public record ResponseEligibility(
            int totalDetections,
            int eligibleDetections,
            boolean responsePolicyEnabled,
            RiskLevel minimumRiskLevel,
            boolean animalPresent
    ) {
        public ResponseEligibility {
            if (totalDetections < 0
                    || eligibleDetections < 0
                    || eligibleDetections > totalDetections
                    || animalPresent != (eligibleDetections > 0)) {
                throw new IllegalArgumentException("response eligibility counts are inconsistent");
            }
        }
    }
}
