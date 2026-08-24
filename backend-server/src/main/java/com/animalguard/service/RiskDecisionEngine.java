package com.animalguard.service;

import com.animalguard.config.RiskProperties;
import com.animalguard.domain.RiskLevel;
import com.animalguard.dto.DetectionEventRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RiskDecisionEngine {

    private final RiskProperties properties;

    public RiskAssessment decide(List<DetectionEventRequest.Detection> detections) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        Map.Entry<String, Integer> highestClassScore = detections.stream()
                .map(detection -> Map.entry(
                        detection.classCode(),
                        properties.classScores().getOrDefault(detection.classCode(), 0)
                ))
                .max(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .orElse(null);
        if (highestClassScore != null && highestClassScore.getValue() > 0) {
            score += highestClassScore.getValue();
            reasons.add("CLASS_SCORE_%s +%d".formatted(
                    highestClassScore.getKey(),
                    highestClassScore.getValue()
            ));
        }

        if (detections.size() >= properties.countThreshold()) {
            score += properties.countScore();
            reasons.add("DETECTION_COUNT_GE_%d +%d".formatted(
                    properties.countThreshold(),
                    properties.countScore()
            ));
        }

        boolean hasHighConfidenceDetection = detections.stream()
                .anyMatch(detection -> detection.detectionConfidence() >= properties.confidenceThreshold()
                        && detection.classificationConfidence() >= properties.confidenceThreshold());
        if (hasHighConfidenceDetection) {
            score += properties.confidenceScore();
            reasons.add("CONFIDENCE_GE_%s +%d".formatted(
                    formatThreshold(properties.confidenceThreshold()),
                    properties.confidenceScore()
            ));
        }

        int boundedScore = Math.min(100, Math.max(0, score));
        RiskLevel riskLevel = toRiskLevel(boundedScore);
        reasons.add("INSIDE_FIELD_NOT_EVALUATED");

        return new RiskAssessment(boundedScore, riskLevel, String.join(", ", reasons));
    }

    RiskLevel toRiskLevel(int score) {
        if (score < properties.mediumThreshold()) {
            return RiskLevel.LOW;
        }
        if (score < properties.highThreshold()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.HIGH;
    }

    private String formatThreshold(double threshold) {
        return BigDecimal.valueOf(threshold).stripTrailingZeros().toPlainString().replace('.', '_');
    }

    public record RiskAssessment(int score, RiskLevel level, String reason) {
    }
}
