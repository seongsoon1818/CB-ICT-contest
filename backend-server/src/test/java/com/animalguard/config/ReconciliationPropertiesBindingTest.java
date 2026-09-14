package com.animalguard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "animalguard.reconciliation.scan-interval=1s",
                    "animalguard.reconciliation.published-timeout=15s",
                    "animalguard.reconciliation.acknowledged-timeout=15s",
                    "animalguard.reconciliation.max-automatic-attempts-per-session=3"
            );

    @Test
    void bindsAllReconciliationSettings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ReconciliationProperties properties = context.getBean(ReconciliationProperties.class);
            assertThat(properties.scanInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.publishedTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.acknowledgedTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.maxAutomaticAttemptsPerSession()).isEqualTo(3);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"scan-interval", "published-timeout", "acknowledged-timeout"})
    void rejectsNonPositiveDuration(String property) {
        contextRunner.withPropertyValues("animalguard.reconciliation." + property + "=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNonPositiveAttemptLimit() {
        contextRunner.withPropertyValues(
                "animalguard.reconciliation.max-automatic-attempts-per-session=0"
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReconciliationProperties.class)
    static class TestConfiguration {
    }
}
