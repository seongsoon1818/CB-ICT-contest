package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttSubscriptionManagerTest {

    private final MqttCommandTransport transport = mock(MqttCommandTransport.class);
    private final MqttInboundMessageRouter router = mock(MqttInboundMessageRouter.class);
    private final MqttSubscriptionState state = new MqttSubscriptionState();

    @Test
    void subscribesBothTopicsAtQosOneAndResubscribesAfterReconnect() {
        when(transport.isConnected()).thenReturn(true);
        MqttCommandTransport.Callback callback = callback(manager(true));

        callback.connectComplete(false, "tcp://broker:1883");

        verify(transport).subscribe(MqttTopicCodec.ACK_TOPIC_FILTER, 1);
        verify(transport).subscribe(MqttTopicCodec.STATUS_TOPIC_FILTER, 1);
        assertThat(state.areRequiredSubscriptionsActive()).isTrue();

        callback.connectionLost(new RuntimeException("connection lost"));
        assertThat(state.areRequiredSubscriptionsActive()).isFalse();
        callback.connectComplete(true, "tcp://broker:1883");

        verify(transport, times(2)).subscribe(MqttTopicCodec.ACK_TOPIC_FILTER, 1);
        verify(transport, times(2)).subscribe(MqttTopicCodec.STATUS_TOPIC_FILTER, 1);
        assertThat(state.areRequiredSubscriptionsActive()).isTrue();
    }

    @Test
    void keepsReadinessFalseWhenOneSubscriptionFailsAndRetriesOnlyMissingFilter() {
        when(transport.isConnected()).thenReturn(true);
        doThrow(new MqttTransportException("status SUBACK failed"))
                .doNothing()
                .when(transport).subscribe(MqttTopicCodec.STATUS_TOPIC_FILTER, 1);
        MqttSubscriptionManager manager = manager(true);
        MqttCommandTransport.Callback callback = callback(manager);

        callback.connectComplete(false, "tcp://broker:1883");

        assertThat(state.isAckActive()).isTrue();
        assertThat(state.isStatusActive()).isFalse();
        assertThat(new MqttActuationTransportReadiness(properties(true), transport, state).isReady())
                .isFalse();

        manager.ensureSubscribed();

        verify(transport, times(1)).subscribe(MqttTopicCodec.ACK_TOPIC_FILTER, 1);
        verify(transport, times(2)).subscribe(MqttTopicCodec.STATUS_TOPIC_FILTER, 1);
        assertThat(state.areRequiredSubscriptionsActive()).isTrue();
    }

    @Test
    void disabledOrDisconnectedStateDoesNotSubscribeAndResetsReadiness() {
        state.markAckActive();
        state.markStatusActive();
        when(transport.isConnected()).thenReturn(true);
        MqttSubscriptionManager disabled = manager(false);

        callback(disabled).connectComplete(false, "tcp://broker:1883");

        verify(transport, never()).subscribe(anyString(), anyInt());
        assertThat(state.areRequiredSubscriptionsActive()).isFalse();
    }

    @Test
    void forwardsInboundMessagesToRouterWithoutPahoTypes() {
        MqttCommandTransport.Callback callback = callback(manager(true));
        byte[] payload = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        callback.messageArrived("animalguard/devices/pi-001/acks", payload, 1, false);

        verify(router).route("animalguard/devices/pi-001/acks", payload, 1, false);
    }

    private MqttSubscriptionManager manager(boolean enabled) {
        return new MqttSubscriptionManager(
                properties(enabled),
                transport,
                state,
                router,
                Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private MqttCommandTransport.Callback callback(MqttSubscriptionManager manager) {
        ArgumentCaptor<MqttCommandTransport.Callback> captor =
                ArgumentCaptor.forClass(MqttCommandTransport.Callback.class);
        verify(transport).setCallback(captor.capture());
        return captor.getValue();
    }

    private MqttProperties properties(boolean enabled) {
        return new MqttProperties(
                enabled,
                "broker.internal",
                1883,
                "backend-test",
                "",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                20
        );
    }
}
