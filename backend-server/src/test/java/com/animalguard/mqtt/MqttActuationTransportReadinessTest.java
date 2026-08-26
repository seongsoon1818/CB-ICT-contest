package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MqttActuationTransportReadinessTest {

    private final MqttCommandTransport transport = mock(MqttCommandTransport.class);
    private final MqttSubscriptionState subscriptionState = new MqttSubscriptionState();

    @Test
    void isFalseWhenMqttIsDisabledEvenIfClientIsConnected() {
        when(transport.isConnected()).thenReturn(true);

        subscriptionState.markAckActive();
        subscriptionState.markStatusActive();

        assertThat(readiness(false).isReady()).isFalse();
    }

    @Test
    void isFalseWhenMqttIsEnabledButDisconnected() {
        when(transport.isConnected()).thenReturn(false);

        subscriptionState.markAckActive();
        subscriptionState.markStatusActive();

        assertThat(readiness(true).isReady()).isFalse();
    }

    @Test
    void isFalseWhenConnectedButEitherSubscriptionIsInactive() {
        when(transport.isConnected()).thenReturn(true);
        subscriptionState.markAckActive();

        assertThat(readiness(true).isReady()).isFalse();

        subscriptionState.reset();
        subscriptionState.markStatusActive();

        assertThat(readiness(true).isReady()).isFalse();
    }

    @Test
    void isTrueOnlyWhenEnabledConnectedAndBothSubscriptionsAreActive() {
        when(transport.isConnected()).thenReturn(true);
        subscriptionState.markAckActive();
        subscriptionState.markStatusActive();

        assertThat(readiness(true).isReady()).isTrue();
    }

    private MqttActuationTransportReadiness readiness(boolean enabled) {
        return new MqttActuationTransportReadiness(properties(enabled), transport, subscriptionState);
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
