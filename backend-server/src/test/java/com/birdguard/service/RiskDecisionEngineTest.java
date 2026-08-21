package com.birdguard.service;

import com.birdguard.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskDecisionEngineTest {

    private final RiskDecisionEngine engine = new RiskDecisionEngine();

    @Test
    void classifiesRiskLevelAtEachBoundary() {
        assertThat(engine.toRiskLevel(39)).isEqualTo(RiskLevel.LOW);
        assertThat(engine.toRiskLevel(40)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(engine.toRiskLevel(69)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(engine.toRiskLevel(70)).isEqualTo(RiskLevel.HIGH);
    }
}
