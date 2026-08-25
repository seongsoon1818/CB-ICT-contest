package com.animalguard.service;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.repository.DetectionEventRepository;
import com.animalguard.repository.RiskDecisionRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=true"
})
@ActiveProfiles("test")
@Import(DeviceCommandCreationServiceIntegrationTest.TestBeans.class)
class DeviceCommandCreationServiceIntegrationTest {

    private static final Instant TEST_NOW = Instant.parse("2026-08-25T03:00:00Z");

    @Autowired
    private DeviceCommandCreationService service;

    @Autowired
    private DeviceCommandRepository deviceCommandRepository;

    @Autowired
    private DetectionEventRepository detectionEventRepository;

    @Autowired
    private RiskDecisionRepository riskDecisionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MutableClock clock;

    @Autowired
    private MutableActuationTransportReadiness transportReadiness;

    @BeforeEach
    void cleanDatabase() {
        clock.set(TEST_NOW);
        transportReadiness.setReady(true);
        deviceCommandRepository.deleteAll();
        riskDecisionRepository.deleteAll();
        detectionEventRepository.deleteAll();
    }

    @Test
    void createsCallerSelectedAutomaticCommandWhenDeviceIsIdle() {
        DetectionEvent event = saveEvent("event-created", "cam-001");

        CommandDecision decision = createInTransaction(
                event,
                "cam-001",
                DeviceCommandType.DETERRENT_FULL,
                "CLASS_SCORE_MAGPIE +30"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(decision.commandId()).isNotBlank();
        assertThat(decision.blockers()).isEmpty();

        DeviceCommand command = deviceCommandRepository.findAll().get(0);
        assertThat(command.getDeviceId()).isEqualTo("pi-001");
        assertThat(command.getSource()).isEqualTo(DeviceCommandSource.AUTOMATIC);
        assertThat(command.getCommandType()).isEqualTo(DeviceCommandType.DETERRENT_FULL);
        assertThat(command.getDurationMs()).isEqualTo(5_000);
        assertThat(command.getReason()).isEqualTo("CLASS_SCORE_MAGPIE +30");
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(command.getIssuedAt()).isEqualTo(TEST_NOW);
        assertThat(command.getExpiresAt()).isEqualTo(TEST_NOW.plusSeconds(10));
    }

    @Test
    void suppressesSecondCommandDuringDeviceCooldown() {
        createInTransaction(
                saveEvent("event-cooldown-first", "cam-001"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );

        CommandDecision second = createInTransaction(
                saveEvent("event-cooldown-second", "cam-001"),
                "cam-001",
                DeviceCommandType.DETERRENT_FULL,
                "PERSISTENT_ANIMAL_DETECTION"
        );

        assertThat(second.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(second.commandId()).isNull();
        assertThat(second.blockers()).containsExactly(ActuationBlocker.COOLDOWN_ACTIVE);
        assertThat(deviceCommandRepository.count()).isEqualTo(1);
    }

    @Test
    void createsNewCommandAtCooldownBoundary() {
        createInTransaction(
                saveEvent("event-boundary-first", "cam-001"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );
        clock.advance(Duration.ofSeconds(20));

        CommandDecision second = createInTransaction(
                saveEvent("event-boundary-second", "cam-001"),
                "cam-001",
                DeviceCommandType.DETERRENT_FULL,
                "PERSISTENT_ANIMAL_DETECTION"
        );

        assertThat(second.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(second.commandId()).isNotBlank();
        assertThat(second.blockers()).isEmpty();
        assertThat(deviceCommandRepository.count()).isEqualTo(2);
    }

    @Test
    void calculatesCooldownIndependentlyForDifferentDevices() {
        createInTransaction(
                saveEvent("event-device-one", "cam-001"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );

        CommandDecision secondDevice = createInTransaction(
                saveEvent("event-device-two", "cam-002"),
                "cam-002",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );

        assertThat(secondDevice.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(deviceCommandRepository.findAll())
                .extracting(DeviceCommand::getDeviceId)
                .containsExactlyInAnyOrder("pi-001", "pi-002");
    }

    @Test
    void suppressesCommandForUnmappedCamera() {
        CommandDecision decision = createInTransaction(
                saveEvent("event-unmapped", "cam-unknown"),
                "cam-unknown",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_UNMAPPED);
        assertThat(deviceCommandRepository.count()).isZero();
    }

    @Test
    void suppressesCommandWhenGlobalPreflightIsNotReady() {
        transportReadiness.setReady(false);

        CommandDecision decision = createInTransaction(
                saveEvent("event-preflight", "cam-001"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        assertThat(deviceCommandRepository.count()).isZero();
    }

    @Test
    void serializesConcurrentCommandCreationForSameDeviceThroughTransactionCompletion() throws Exception {
        DetectionEvent firstEvent = saveEvent("event-concurrent-first", "cam-001");
        DetectionEvent secondEvent = saveEvent("event-concurrent-second", "cam-001");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<CommandDecision> first = executor.submit(() -> createConcurrently(firstEvent, ready, start));
            Future<CommandDecision> second = executor.submit(() -> createConcurrently(secondEvent, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<CommandDecision> decisions = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(decisions)
                    .extracting(CommandDecision::outcome)
                    .containsExactlyInAnyOrder(CommandOutcome.CREATED, CommandOutcome.SUPPRESSED);
            assertThat(decisions)
                    .filteredOn(decision -> decision.outcome() == CommandOutcome.SUPPRESSED)
                    .singleElement()
                    .extracting(CommandDecision::blockers)
                    .isEqualTo(List.of(ActuationBlocker.COOLDOWN_ACTIVE));
        } finally {
            executor.shutdownNow();
        }

        assertThat(deviceCommandRepository.count()).isEqualTo(1);
    }

    private DetectionEvent saveEvent(String eventId, String cameraId) {
        return transactionTemplate.execute(status -> detectionEventRepository.saveAndFlush(new DetectionEvent(
                eventId,
                cameraId,
                TEST_NOW,
                1280,
                720,
                "animal-detector-v1",
                null
        )));
    }

    private CommandDecision createInTransaction(
            DetectionEvent event,
            String cameraId,
            DeviceCommandType commandType,
            String reason
    ) {
        return transactionTemplate.execute(status -> service.createIfAllowed(event, cameraId, commandType, reason));
    }

    private CommandDecision createConcurrently(
            DetectionEvent event,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent command start timed out");
        }
        return createInTransaction(
                event,
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "FIRST_ANIMAL_DETECTION"
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(TEST_NOW);
        }

        @Bean
        MutableActuationTransportReadiness mutableActuationTransportReadiness() {
            return new MutableActuationTransportReadiness();
        }
    }

    static final class MutableActuationTransportReadiness implements ActuationTransportReadiness {

        private final AtomicBoolean ready = new AtomicBoolean();

        void setReady(boolean ready) {
            this.ready.set(ready);
        }

        @Override
        public boolean isReady() {
            return ready.get();
        }
    }

    static final class MutableClock extends Clock {

        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
        }

        void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
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
