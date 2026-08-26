package com.animalguard.config;

import com.animalguard.mqtt.MqttActuationTransportReadiness;
import com.animalguard.mqtt.MqttCommandTransport;
import com.animalguard.mqtt.MqttSubscriptionState;
import com.animalguard.service.ActuationTransportReadiness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MqttConfigurationTest {

    @Test
    void disabledConfigurationStartsAndRealReadinessOverridesFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(DeviceControlConfiguration.class, MqttConfiguration.class)
                .withPropertyValues(
                        "animalguard.device-control.cooldown=20s",
                        "animalguard.device-control.command-ttl=10s",
                        "animalguard.mqtt.enabled=false",
                        "animalguard.mqtt.host=127.0.0.1",
                        "animalguard.mqtt.port=1883",
                        "animalguard.mqtt.client-id=animalguard-backend",
                        "animalguard.mqtt.connect-timeout=5s",
                        "animalguard.mqtt.publish-timeout=5s",
                        "animalguard.mqtt.dispatch-interval=500ms",
                        "animalguard.mqtt.dispatch-batch-size=20"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MqttCommandTransport.class);
                    assertThat(context).hasSingleBean(MqttSubscriptionState.class);
                    assertThat(context).hasSingleBean(MqttActuationTransportReadiness.class);
                    assertThat(context.getBean(ActuationTransportReadiness.class).isReady()).isFalse();
                });
    }
}
