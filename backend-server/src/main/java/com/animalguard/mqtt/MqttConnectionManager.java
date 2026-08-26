package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class MqttConnectionManager {

    private static final Duration WARNING_INTERVAL = Duration.ofSeconds(30);

    private final MqttProperties properties;
    private final MqttCommandTransport transport;
    private final Clock clock;
    private Instant lastWarningAt;

    @Scheduled(fixedDelayString = "${animalguard.mqtt.dispatch-interval:500ms}")
    public void ensureConnected() {
        if (!properties.enabled() || transport.isConnected()) {
            return;
        }
        try {
            transport.connect();
        } catch (MqttTransportException exception) {
            Instant now = clock.instant();
            if (shouldWarn(now)) {
                log.warn(
                        "MQTT broker connection unavailable: host={}, port={}, reason={}",
                        properties.host(),
                        properties.port(),
                        exception.getMessage()
                );
            }
        }
    }

    private synchronized boolean shouldWarn(Instant now) {
        if (lastWarningAt != null && now.isBefore(lastWarningAt.plus(WARNING_INTERVAL))) {
            return false;
        }
        lastWarningAt = now;
        return true;
    }
}
