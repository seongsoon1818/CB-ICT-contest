package com.birdguard.service;

import com.birdguard.domain.RiskLevel;
import com.birdguard.dto.DetectionEventRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class RiskDecisionEngine {

    public RiskAssessment decide(List<DetectionEventRequest.Bird> birds) {
        int score = 0;
        List<String> reasons = new ArrayList<>();

        boolean containsMagpie = birds.stream()
                .anyMatch(bird -> "MAGPIE".equalsIgnoreCase(bird.speciesCode()));
        if (containsMagpie) {
            score += 30;
            reasons.add("MAGPIE +30");
        }

        if (birds.size() >= 3) {
            score += 20;
            reasons.add("BIRD_COUNT_GE_3 +20");
        }

        boolean containsHighConfidenceBird = birds.stream()
                .anyMatch(bird -> bird.detectionConfidence() >= 0.9
                        && bird.classificationConfidence() >= 0.9);
        if (containsHighConfidenceBird) {
            score += 20;
            reasons.add("CONFIDENCE_GE_0_9 +20");
        }

        int boundedScore = Math.min(100, Math.max(0, score));
        RiskLevel riskLevel = toRiskLevel(boundedScore);
        reasons.add("INSIDE_FIELD=false");

        return new RiskAssessment(boundedScore, riskLevel, String.join(", ", reasons));
    }

    private RiskLevel toRiskLevel(int score) {
        if (score < 40) {
            return RiskLevel.LOW;
        }
        if (score < 70) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.HIGH;
    }

    public record RiskAssessment(int score, RiskLevel level, String reason) {
    }
}
