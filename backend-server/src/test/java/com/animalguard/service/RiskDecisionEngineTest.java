package com.animalguard.service;

import com.animalguard.config.RiskProperties;
import com.animalguard.domain.RiskLevel;
import com.animalguard.dto.DetectionEventRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskDecisionEngineTest {

    private final RiskDecisionEngine engine = new RiskDecisionEngine(new RiskProperties(
            Map.of("MAGPIE", 30, "WILD_BOAR", 60, "UNKNOWN", 0),
            3,
            20,
            0.90,
            20,
            40,
            70
    ));

    @Test
    void classifiesRiskLevelAtEachBoundary() {
        assertThat(engine.toRiskLevel(39)).isEqualTo(RiskLevel.LOW);
        assertThat(engine.toRiskLevel(40)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(engine.toRiskLevel(69)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(engine.toRiskLevel(70)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void usesConfiguredRiskLevelBoundaries() {
        RiskDecisionEngine customBoundaryEngine = new RiskDecisionEngine(new RiskProperties(
                Map.of("UNKNOWN", 0),
                3,
                20,
                0.90,
                20,
                25,
                80
        ));

        assertThat(customBoundaryEngine.toRiskLevel(24)).isEqualTo(RiskLevel.LOW);
        assertThat(customBoundaryEngine.toRiskLevel(25)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(customBoundaryEngine.toRiskLevel(79)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(customBoundaryEngine.toRiskLevel(80)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void appliesConfiguredMagpieClassScore() {
        assertThat(engine.decide(List.of(detection("MAGPIE", 0.80, 0.80))).score()).isEqualTo(30);
    }

    @Test
    void appliesTestOnlyWildBoarClassScore() {
        assertThat(engine.decide(List.of(detection("WILD_BOAR", 0.80, 0.80))).score()).isEqualTo(60);
    }

    @Test
    void treatsUnconfiguredClassCodeAsZero() {
        assertThat(engine.decide(List.of(detection("WATER_DEER", 0.80, 0.80))).score()).isZero();
    }

    @Test
    void appliesOnlyHighestClassScoreOnce() {
        RiskDecisionEngine.RiskAssessment assessment = engine.decide(List.of(
                detection("MAGPIE", 0.80, 0.80),
                detection("WILD_BOAR", 0.80, 0.80)
        ));

        assertThat(assessment.score()).isEqualTo(60);
        assertThat(assessment.reason()).contains("CLASS_SCORE_WILD_BOAR +60");
        assertThat(assessment.reason()).doesNotContain("CLASS_SCORE_MAGPIE");
    }

    @Test
    void appliesDetectionCountScoreOnceAtThreshold() {
        RiskDecisionEngine.RiskAssessment assessment = engine.decide(List.of(
                detection("SPARROW", 0.80, 0.80),
                detection("RODENT", 0.80, 0.80),
                detection("WATER_DEER", 0.80, 0.80)
        ));

        assertThat(assessment.score()).isEqualTo(20);
        assertThat(assessment.reason()).contains("DETECTION_COUNT_GE_3 +20");
    }

    @Test
    void appliesConfidenceScoreWhenBothConfidencesMeetThreshold() {
        RiskDecisionEngine.RiskAssessment assessment = engine.decide(
                List.of(detection("SPARROW", 0.90, 0.91))
        );

        assertThat(assessment.score()).isEqualTo(20);
        assertThat(assessment.reason()).contains("CONFIDENCE_GE_0_9 +20");
    }

    private DetectionEventRequest.Detection detection(
            String classCode,
            double detectionConfidence,
            double classificationConfidence
    ) {
        return new DetectionEventRequest.Detection(
                "det-001",
                null,
                classCode,
                detectionConfidence,
                classificationConfidence,
                new DetectionEventRequest.Bbox(10, 20, 30, 40)
        );
    }
}
