package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Component
@Slf4j
public class MqttSubscriptionManager {

    private static final int SUBSCRIPTION_QOS = 1;
    private static final Duration WARNING_INTERVAL = Duration.ofSeconds(30);

    private final MqttProperties properties;
    private final MqttCommandTransport transport;
    private final MqttSubscriptionState state;
    private final MqttInboundMessageRouter router;
    private final Clock clock;
    private Instant lastWarningAt;

    public MqttSubscriptionManager(
            MqttProperties properties,
            MqttCommandTransport transport,
            MqttSubscriptionState state,
            MqttInboundMessageRouter router,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.state = Objects.requireNonNull(state, "state must not be null");
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        transport.setCallback(new SubscriberCallback());
    }

    @Scheduled(fixedDelayString = "${animalguard.mqtt.dispatch-interval:500ms}")
    public synchronized void ensureSubscribed() {
        if (!properties.enabled() || !transport.isConnected()) {
            state.reset();
            return;
        }
        subscribeMissing();
    }

    private void subscribeMissing() {
        if (!state.isAckActive()) {
            subscribe(MqttTopicCodec.ACK_TOPIC_FILTER, state::markAckActive);
        }
        if (!state.isStatusActive()) {
            subscribe(MqttTopicCodec.STATUS_TOPIC_FILTER, state::markStatusActive);
        }
    }

    private void subscribe(String topicFilter, Runnable markActive) {
        try {
            transport.subscribe(topicFilter, SUBSCRIPTION_QOS);
            markActive.run();
        } catch (MqttTransportException exception) {
            Instant now = clock.instant();
            if (shouldWarn(now)) {
                log.warn(
                        "MQTT subscription unavailable: topicFilter={}, reason={}",
                        topicFilter,
                        exception.getMessage()
                );
            }
        }
    }

    private boolean shouldWarn(Instant now) {
        if (lastWarningAt != null && now.isBefore(lastWarningAt.plus(WARNING_INTERVAL))) {
            return false;
        }
        lastWarningAt = now;
        return true;
    }

    private synchronized void connected() {
        state.reset();
        if (properties.enabled() && transport.isConnected()) {
            subscribeMissing();
        }
    }

    private synchronized void disconnected() {
        state.reset();
    }

    private final class SubscriberCallback implements MqttCommandTransport.Callback {

        @Override
        public void connectComplete(boolean reconnect, String serverUri) {
            connected();
        }

        @Override
        public void connectionLost(Throwable cause) {
            disconnected();
        }

        @Override
        public void messageArrived(String topic, byte[] payload, int qos, boolean retained) {
            router.route(topic, payload, qos, retained);
        }
    }
}
