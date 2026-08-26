package com.animalguard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "animalguard.observation.persistence-threshold=5s",
                    "animalguard.observation.absence-grace=2s",
                    "animalguard.observation.continuity-timeout=3s",
                    "animalguard.observation.sound-alert-duration=2s",
                    "animalguard.observation.deterrent-full-duration=5s"
            );

    @Test
    void bindsDurationsAndConvertsCommandDurationsToMilliseconds() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ObservationProperties properties = context.getBean(ObservationProperties.class);
            assertThat(properties.persistenceThreshold()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.absenceGrace()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.continuityTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.soundAlertDurationMs()).isEqualTo(2_000);
            assertThat(properties.deterrentFullDurationMs()).isEqualTo(5_000);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistence-threshold", "absence-grace", "continuity-timeout",
            "sound-alert-duration", "deterrent-full-duration"})
    void rejectsNonPositiveDuration(String property) {
        contextRunner.withPropertyValues("animalguard.observation." + property + "=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsContinuityTimeoutShorterThanAbsenceGrace() {
        contextRunner.withPropertyValues("animalguard.observation.continuity-timeout=1999ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sound-alert-duration", "deterrent-full-duration"})
    void rejectsCommandDurationOutsideIntegerMilliseconds(String property) {
        contextRunner.withPropertyValues("animalguard.observation." + property + "=2147483648ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ObservationProperties.class)
    static class TestConfiguration {
    }
}
