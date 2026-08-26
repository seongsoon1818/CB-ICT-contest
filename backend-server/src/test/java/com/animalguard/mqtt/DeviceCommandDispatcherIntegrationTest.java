package com.animalguard.mqtt;

import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DetectionEventRepository;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "animalguard.mqtt.enabled=true",
        "animalguard.mqtt.dispatch-interval=1h",
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=true",
        "animalguard.response-policy.enabled=true",
        "animalguard.response-policy.allowed-class-codes=MAGPIE",
        "animalguard.operator-api.enabled=true",
        "animalguard.operator-api.token=fake-test-operator-token"
})
@ActiveProfiles("test")
@Import(DeviceCommandDispatcherIntegrationTest.TestBeans.class)
class DeviceCommandDispatcherIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:05Z");

    @Autowired
    private DeviceCommandDispatcher dispatcher;

    @Autowired
    private DeviceCommandRepository commandRepository;

    @Autowired
    private DetectionEventRepository eventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ObservingTransport transport;

    @Autowired
    private MqttSubscriptionState subscriptionState;

    @BeforeEach
    void cleanDatabase() {
        transport.reset();
        subscriptionState.reset();
        subscriptionState.markAckActive();
        subscriptionState.markStatusActive();
        commandRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void transportObservesPublishedOnlyAfterTransactionCommit() {
        saveCreatedCommand("command-commit-order");

        dispatcher.dispatch();

        assertThat(transport.observedStatus).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(transport.publishCount).isEqualTo(1);
        assertThat(transport.qos).isEqualTo(1);
        assertThat(transport.retained).isFalse();
        assertThat(transport.payload).contains("\"commandId\":\"command-commit-order\"");
        assertThat(command("command-commit-order").getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
    }

    @Test
    void immediateTransportFailureCommitsFailedInSecondTransaction() {
        saveCreatedCommand("command-immediate-failure");
        transport.failPublish = true;

        dispatcher.dispatch();

        DeviceCommand failed = command("command-immediate-failure");
        assertThat(transport.observedStatus).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(transport.publishCount).isEqualTo(1);
        assertThat(failed.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(failed.getPublishedAt()).isEqualTo(NOW);
        assertThat(failed.getFailedAt()).isEqualTo(NOW);
        assertThat(failed.getFailedReportedAt()).isNull();
    }

    @Test
    void dispatchesCreatedManualCommandThroughSameCommitBoundary() {
        saveManualCommand("manual-15356786-9588-4db4-a0fe-f8acd6300868");

        dispatcher.dispatch();

        assertThat(transport.observedStatus).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(transport.publishCount).isEqualTo(1);
        assertThat(transport.payload)
                .contains("\"eventId\":null")
                .contains("\"source\":\"MANUAL\"")
                .contains("\"durationMs\":null");
        assertThat(command("manual-15356786-9588-4db4-a0fe-f8acd6300868").getStatus())
                .isEqualTo(DeviceCommandStatus.PUBLISHED);
    }

    private void saveCreatedCommand(String commandId) {
        transactionTemplate.executeWithoutResult(status -> {
            DetectionEvent event = eventRepository.save(new DetectionEvent(
                    UUID.randomUUID().toString(),
                    "cam-001",
                    NOW.minusSeconds(5),
                    1280,
                    720,
                    "animal-detector-v1",
                    null
            ));
            commandRepository.save(new DeviceCommand(
                    commandId,
                    event,
                    "pi-001",
                    DeviceCommandSource.AUTOMATIC,
                    DeviceCommandType.SOUND_ALERT,
                    2_000,
                    "FIRST_ANIMAL_DETECTION",
                    NOW.minusSeconds(5),
                    NOW.plusSeconds(5)
            ));
        });
    }

    private void saveManualCommand(String commandId) {
        transactionTemplate.executeWithoutResult(status -> commandRepository.save(new DeviceCommand(
                commandId,
                null,
                "pi-001",
                DeviceCommandSource.MANUAL,
                DeviceCommandType.ROTATE_CAMERA_LEFT,
                null,
                "USER_REQUEST",
                NOW.minusSeconds(5),
                NOW.plusSeconds(5)
        )));
    }

    private DeviceCommand command(String commandId) {
        return commandRepository.findByCommandId(commandId).orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        Clock dispatchClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ObservingTransport observingTransport(DeviceCommandRepository repository) {
            return new ObservingTransport(repository);
        }
    }

    static final class ObservingTransport implements MqttCommandTransport {

        private final DeviceCommandRepository repository;
        private int publishCount;
        private DeviceCommandStatus observedStatus;
        private String payload;
        private int qos;
        private boolean retained;
        private boolean failPublish;

        private ObservingTransport(DeviceCommandRepository repository) {
            this.repository = repository;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void publish(String topic, byte[] payload, int qos, boolean retained) {
            String json = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            String commandId = json.replaceFirst(".*\\\"commandId\\\":\\\"([^\\\"]+)\\\".*", "$1");
            publishCount++;
            observedStatus = repository.findByCommandId(commandId).orElseThrow().getStatus();
            this.payload = json;
            this.qos = qos;
            this.retained = retained;
            if (failPublish) {
                throw new MqttTransportException("simulated immediate failure");
            }
        }

        @Override
        public void subscribe(String topicFilter, int qos) {
        }

        @Override
        public void setCallback(Callback callback) {
        }

        @Override
        public void close() {
        }

        private void reset() {
            publishCount = 0;
            observedStatus = null;
            payload = null;
            qos = 0;
            retained = false;
            failPublish = false;
        }
    }
}
