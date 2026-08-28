package com.animalguard.config;

import com.animalguard.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ResponsePolicyPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void defaultsToFailClosedDisabledPolicy() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ResponsePolicyProperties properties = context.getBean(ResponsePolicyProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.allowedClassCodes()).isEmpty();
            assertThat(properties.minimumDetectionConfidence()).isZero();
            assertThat(properties.minimumClassificationConfidence()).isNull();
            assertThat(properties.minimumRiskScore()).isNull();
            assertThat(properties.minimumRiskLevel()).isNull();
        });
    }

    @Test
    void rejectsEnabledPolicyWithEmptyAllowlist() {
        contextRunner
                .withPropertyValues("animalguard.response-policy.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void bindsAndSortsConfiguredPolicy() {
        contextRunner
                .withPropertyValues(
                        "animalguard.response-policy.enabled=true",
                        "animalguard.response-policy.allowed-class-codes=WILD_BOAR,MAGPIE",
                        "animalguard.response-policy.minimum-detection-confidence=0.7",
                        "animalguard.response-policy.minimum-classification-confidence=0.8",
                        "animalguard.response-policy.minimum-risk-score=50",
                        "animalguard.response-policy.minimum-risk-level=MEDIUM"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ResponsePolicyProperties properties = context.getBean(ResponsePolicyProperties.class);
                    assertThat(properties.allowedClassCodes()).containsExactly("MAGPIE", "WILD_BOAR");
                    assertThat(properties.minimumDetectionConfidence()).isEqualTo(0.7);
                    assertThat(properties.minimumClassificationConfidence()).isEqualTo(0.8);
                    assertThat(properties.minimumRiskScore()).isEqualTo(50);
                    assertThat(properties.minimumRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
                });
    }

    @Test
    void rejectsInvalidClassCodeAndConfidenceRanges() {
        contextRunner
                .withPropertyValues(
                        "animalguard.response-policy.enabled=true",
                        "animalguard.response-policy.allowed-class-codes=wild-boar"
                )
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "animalguard.response-policy.enabled=true",
                        "animalguard.response-policy.allowed-class-codes=MAGPIE",
                        "animalguard.response-policy.minimum-detection-confidence=1.01"
                )
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "animalguard.response-policy.enabled=true",
                        "animalguard.response-policy.allowed-class-codes=MAGPIE",
                        "animalguard.response-policy.minimum-classification-confidence=-0.01"
                )
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "animalguard.response-policy.enabled=true",
                        "animalguard.response-policy.allowed-class-codes=MAGPIE",
                        "animalguard.response-policy.minimum-risk-score=101"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnknownMinimumRiskLevel() {
        contextRunner
                .withPropertyValues(
                        "animalguard.response-policy.enabled=true",
                        "animalguard.response-policy.allowed-class-codes=MAGPIE",
                        "animalguard.response-policy.minimum-risk-level=CRITICAL"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ResponsePolicyProperties.class)
    static class TestConfiguration {
    }
}
