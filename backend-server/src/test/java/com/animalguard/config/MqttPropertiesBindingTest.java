package com.animalguard.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MqttPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MqttPropertiesConfiguration.class)
            .withPropertyValues(
                    "animalguard.mqtt.enabled=false",
                    "animalguard.mqtt.host=127.0.0.1",
                    "animalguard.mqtt.port=1883",
                    "animalguard.mqtt.client-id=animalguard-backend",
                    "animalguard.mqtt.username=",
                    "animalguard.mqtt.password=",
                    "animalguard.mqtt.connect-timeout=5s",
                    "animalguard.mqtt.publish-timeout=5s",
                    "animalguard.mqtt.dispatch-interval=500ms",
                    "animalguard.mqtt.dispatch-batch-size=20"
            );

    @Test
    void bindsEveryMqttSetting() {
        contextRunner
                .withPropertyValues(
                        "animalguard.mqtt.enabled=true",
                        "animalguard.mqtt.host=broker.internal",
                        "animalguard.mqtt.port=2883",
                        "animalguard.mqtt.client-id=backend-test",
                        "animalguard.mqtt.username=operator",
                        "animalguard.mqtt.password=top-secret",
                        "animalguard.mqtt.connect-timeout=3s",
                        "animalguard.mqtt.publish-timeout=2s",
                        "animalguard.mqtt.dispatch-interval=750ms",
                        "animalguard.mqtt.dispatch-batch-size=7"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MqttProperties properties = context.getBean(MqttProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.host()).isEqualTo("broker.internal");
                    assertThat(properties.port()).isEqualTo(2883);
                    assertThat(properties.clientId()).isEqualTo("backend-test");
                    assertThat(properties.username()).isEqualTo("operator");
                    assertThat(properties.password()).isEqualTo("top-secret");
                    assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(properties.publishTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.dispatchInterval()).isEqualTo(Duration.ofMillis(750));
                    assertThat(properties.dispatchBatchSize()).isEqualTo(7);
                    assertThat(properties.toString()).doesNotContain("top-secret");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "65536"})
    void rejectsPortOutsideMqttRange(String port) {
        assertBindingFails("animalguard.mqtt.port=" + port);
    }

    @Test
    void rejectsBlankHost() {
        assertBindingFails("animalguard.mqtt.host=");
    }

    @Test
    void rejectsBlankClientId() {
        assertBindingFails("animalguard.mqtt.client-id=");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "animalguard.mqtt.connect-timeout=0s",
            "animalguard.mqtt.connect-timeout=-1s",
            "animalguard.mqtt.publish-timeout=0s",
            "animalguard.mqtt.publish-timeout=-1s",
            "animalguard.mqtt.dispatch-interval=0s",
            "animalguard.mqtt.dispatch-interval=-1s"
    })
    void rejectsNonPositiveDurations(String property) {
        assertBindingFails(property);
    }

    @Test
    void rejectsNonPositiveDispatchBatchSize() {
        assertBindingFails("animalguard.mqtt.dispatch-batch-size=0");
    }

    private void assertBindingFails(String property) {
        contextRunner
                .withPropertyValues(property)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasMessageContaining("Could not bind properties");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MqttProperties.class)
    static class MqttPropertiesConfiguration {
    }
}
