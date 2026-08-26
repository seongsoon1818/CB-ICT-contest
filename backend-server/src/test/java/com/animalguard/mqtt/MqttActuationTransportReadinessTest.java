package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqttActuationTransportReadinessTest {

    private final MqttCommandTransport transport = mock(MqttCommandTransport.class);

    @Test
    void isFalseWhenMqttIsDisabledEvenIfClientIsConnected() {
        when(transport.isConnected()).thenReturn(true);

        assertThat(new MqttActuationTransportReadiness(properties(false), transport).isReady()).isFalse();
    }

    @Test
    void isFalseWhenMqttIsEnabledButDisconnected() {
        when(transport.isConnected()).thenReturn(false);

        assertThat(new MqttActuationTransportReadiness(properties(true), transport).isReady()).isFalse();
    }

    @Test
    void isTrueOnlyWhenMqttIsEnabledAndConnected() {
        when(transport.isConnected()).thenReturn(true);

        assertThat(new MqttActuationTransportReadiness(properties(true), transport).isReady()).isTrue();
    }

    private MqttProperties properties(boolean enabled) {
        return new MqttProperties(
                enabled,
                "127.0.0.1",
                1883,
                "animalguard-backend",
                "",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                20
        );
    }
}
