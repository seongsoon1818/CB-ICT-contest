package com.animalguard.mqtt;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.animalguard.config.MqttProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MqttConnectionManagerTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

    private final MqttCommandTransport transport = mock(MqttCommandTransport.class);
    private final MutableClock clock = new MutableClock(NOW);
    private final Logger logger = (Logger) LoggerFactory.getLogger(MqttConnectionManager.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void skipsConnectionWhenDisabledOrAlreadyConnected() {
        new MqttConnectionManager(properties(false), transport, clock).ensureConnected();
        verify(transport, never()).connect();

        when(transport.isConnected()).thenReturn(true);
        new MqttConnectionManager(properties(true), transport, clock).ensureConnected();
        verify(transport, never()).connect();
    }

    @Test
    void retriesInitialConnectionWhileDisconnected() {
        MqttConnectionManager manager = new MqttConnectionManager(properties(true), transport, clock);

        manager.ensureConnected();
        manager.ensureConnected();

        verify(transport, org.mockito.Mockito.times(2)).connect();
    }

    @Test
    void rateLimitsConnectionWarningsAndDoesNotLogPasswordOrStackTrace() {
        org.mockito.Mockito.doThrow(new MqttTransportException("broker unavailable"))
                .when(transport).connect();
        MqttConnectionManager manager = new MqttConnectionManager(properties(true), transport, clock);

        manager.ensureConnected();
        clock.advance(Duration.ofSeconds(29));
        manager.ensureConnected();
        clock.advance(Duration.ofSeconds(1));
        manager.ensureConnected();

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list).allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .contains("broker.internal", "broker unavailable")
                    .doesNotContain("top-secret");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private MqttProperties properties(boolean enabled) {
        return new MqttProperties(
                enabled,
                "broker.internal",
                1883,
                "backend-test",
                "operator",
                "top-secret",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                20
        );
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            current = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current.get(), zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
