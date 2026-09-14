package com.animalguard.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RiskPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RiskPropertiesConfiguration.class)
            .withPropertyValues(
                    "animalguard.risk.class-scores.UNKNOWN=0",
                    "animalguard.risk.count-threshold=3",
                    "animalguard.risk.count-score=20",
                    "animalguard.risk.confidence-threshold=0.9",
                    "animalguard.risk.confidence-score=20",
                    "animalguard.risk.medium-threshold=40",
                    "animalguard.risk.high-threshold=70"
            );

    @Test
    void rejectsInvalidClassCodeKeyDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("animalguard.risk.class-scores.[wild-boar]=30")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

    @Test
    void rejectsCountThresholdAboveDetectionLimitDuringConfigurationBinding() {
        contextRunner
                .withPropertyValues("animalguard.risk.count-threshold=101")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RiskProperties.class)
    static class RiskPropertiesConfiguration {
    }
}
