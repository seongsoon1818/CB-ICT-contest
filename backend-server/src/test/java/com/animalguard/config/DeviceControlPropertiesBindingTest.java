package com.animalguard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceControlPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DeviceControlPropertiesConfiguration.class)
            .withPropertyValues(
                    "animalguard.device-control.cooldown=20s",
                    "animalguard.device-control.command-ttl=10s",
                    "animalguard.device-control.camera-device-mappings.cam-001=pi-001"
            );

    @Test
    void bindsCooldownAndCameraDeviceMappings() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            DeviceControlProperties properties = context.getBean(DeviceControlProperties.class);
            assertThat(properties.cooldown()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.commandTtl()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.cameraDeviceMappings())
                    .containsExactlyEntriesOf(Map.of("cam-001", "pi-001"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void rejectsNonPositiveCooldown(String cooldown) {
        contextRunner
                .withPropertyValues("animalguard.device-control.cooldown=" + cooldown)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s"})
    void rejectsNonPositiveCommandTtl(String commandTtl) {
        contextRunner
                .withPropertyValues("animalguard.device-control.command-ttl=" + commandTtl)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

    @Test
    void rejectsInvalidCameraIdMappingKey() {
        contextRunner
                .withPropertyValues("animalguard.device-control.camera-device-mappings.[cam/invalid]=pi-001")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

    @Test
    void rejectsBlankDeviceIdMappingValue() {
        contextRunner
                .withPropertyValues("animalguard.device-control.camera-device-mappings.cam-001=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("Could not bind properties");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DeviceControlProperties.class)
    static class DeviceControlPropertiesConfiguration {
    }
}
