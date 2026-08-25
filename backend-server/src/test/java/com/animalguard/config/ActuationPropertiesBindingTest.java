package com.animalguard.config;

import com.animalguard.service.ActuationTransportReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ActuationPropertiesBindingTest {

    private final ApplicationContextRunner propertiesContextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ActuationPropertiesConfiguration.class);

    @Test
    void defaultsActuationAndRiskPolicyConfirmationToFalse() {
        propertiesContextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ActuationProperties properties = context.getBean(ActuationProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.riskPolicyConfirmed()).isFalse();
        });
    }

    @Test
    void bindsActuationOverrides() {
        propertiesContextRunner
                .withPropertyValues(
                        "animalguard.actuation.enabled=true",
                        "animalguard.actuation.risk-policy-confirmed=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ActuationProperties properties = context.getBean(ActuationProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.riskPolicyConfirmed()).isTrue();
                });
    }

    @Test
    void providesUnavailableTransportReadinessByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeviceControlConfiguration.class)
                .withPropertyValues(
                        "animalguard.device-control.cooldown=20s",
                        "animalguard.device-control.command-ttl=10s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ActuationTransportReadiness.class).isReady()).isFalse();
                });
    }

    @Test
    void regularTransportReadinessOverridesFallbackWithoutPrimary() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DeviceControlConfiguration.class,
                        AvailableTransportConfiguration.class
                )
                .withPropertyValues(
                        "animalguard.device-control.cooldown=20s",
                        "animalguard.device-control.command-ttl=10s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ActuationTransportReadiness.class).isReady()).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ActuationProperties.class)
    static class ActuationPropertiesConfiguration {
    }

    @Configuration(proxyBeanMethods = false)
    static class AvailableTransportConfiguration {

        @Bean
        ActuationTransportReadiness availableActuationTransportReadiness() {
            return () -> true;
        }
    }
}
