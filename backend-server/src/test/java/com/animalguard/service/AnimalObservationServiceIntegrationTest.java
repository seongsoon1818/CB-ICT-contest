package com.animalguard.service;

import com.animalguard.domain.AnimalObservationState;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.AnimalObservationStateRepository;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=true",
        "animalguard.response-policy.enabled=true",
        "animalguard.response-policy.allowed-class-codes=MAGPIE"
})
@ActiveProfiles("test")
@Import(AnimalObservationServiceIntegrationTest.TestBeans.class)
class AnimalObservationServiceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-08-26T00:00:00Z");

    @Autowired
    private AnimalObservationService service;
    @Autowired
    private AnimalObservationStateRepository observationRepository;
    @Autowired
    private DeviceCommandRepository commandRepository;
    @Autowired
    private RiskDecisionRepository riskDecisionRepository;
    @Autowired
    private DetectionEventRepository eventRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private MutableClock clock;
    @Autowired
    private MutableTransportReadiness transportReadiness;

    @BeforeEach
    void cleanDatabase() {
        clock.set(BASE);
        transportReadiness.setReady(true);
        commandRepository.deleteAll();
        observationRepository.deleteAll();
        riskDecisionRepository.deleteAll();
        eventRepository.deleteAll();
    }

    @Test
    void executesFirstPersistenceBriefMissAndDisappearanceFlow() {
        List<AnimalObservationResult> results = List.of(
                process("event-0", 0, true),
                process("event-2", 2, true),
                process("event-5", 5, true),
                process("event-6", 6, false),
                process("event-7", 7, true),
                process("event-8", 8, false),
                process("event-10", 10, false)
        );

        assertThat(results).extracting(AnimalObservationResult::trigger).containsExactly(
                ObservationTrigger.FIRST_DETECTION,
                ObservationTrigger.NONE,
                ObservationTrigger.NONE,
                ObservationTrigger.NONE,
                ObservationTrigger.NONE,
                ObservationTrigger.NONE,
                ObservationTrigger.DISAPPEARANCE_CONFIRMED
        );
        assertThat(results).extracting(AnimalObservationResult::commandType).containsExactly(
                DeviceCommandType.DETERRENT_FULL,
                null,
                null,
                null,
                null,
                null,
                DeviceCommandType.STOP_DETERRENT
        );
        assertThat(commandRepository.findAll()).extracting(command -> command.getCommandType())
                .containsExactly(
                        DeviceCommandType.DETERRENT_FULL,
                        DeviceCommandType.STOP_DETERRENT
                );
        AnimalObservationState state = observationRepository.findByCameraId("cam-001").orElseThrow();
        assertThat(state.getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        assertThat(state.getLastProcessedCapturedAt()).isEqualTo(BASE.plusSeconds(10));
    }

    @Test
    void stopsDeterrentWhenRiskFallsBelowEligibility() {
        process("event-short-positive", 0, true);
        process("event-short-empty-1", 1, false);
        AnimalObservationResult result = process("event-short-empty-3", 3, false);

        assertThat(result.trigger()).isEqualTo(ObservationTrigger.DISAPPEARANCE_CONFIRMED);
        assertThat(result.commandDecision().outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(commandRepository.findAll()).extracting(command -> command.getCommandType())
                .containsExactly(DeviceCommandType.DETERRENT_FULL, DeviceCommandType.STOP_DETERRENT);
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
    }

    @Test
    void ignoresStaleCapturedAtWithoutChangingObservationOrCreatingCommand() {
        process("event-current", 1, true);
        AnimalObservationResult stale = process("event-stale", 0, false);

        assertThat(stale.trigger()).isEqualTo(ObservationTrigger.STALE_EVENT_IGNORED);
        assertThat(stale.commandDecision().outcome()).isEqualTo(CommandOutcome.NOT_REQUESTED);
        assertThat(eventRepository.count()).isEqualTo(2);
        assertThat(commandRepository.count()).isEqualTo(1);
        AnimalObservationState state = observationRepository.findByCameraId("cam-001").orElseThrow();
        assertThat(state.getFirstDetectedAt()).isEqualTo(BASE.plusSeconds(1));
        assertThat(state.getLastProcessedCapturedAt()).isEqualTo(BASE.plusSeconds(1));
    }

    @Test
    void keepsImmediateDeterrentAcrossLongPositiveGap() {
        process("event-continuity-first", 0, true);
        AnimalObservationResult restarted = process("event-continuity-restart", 4, true);

        assertThat(restarted.trigger()).isEqualTo(ObservationTrigger.NONE);
        assertThat(restarted.commandDecision().outcome()).isEqualTo(CommandOutcome.NOT_REQUESTED);
        AnimalObservationState state = observationRepository.findByCameraId("cam-001").orElseThrow();
        assertThat(state.getFirstDetectedAt()).isEqualTo(BASE);
        assertThat(state.getSoundAlertCommandId()).isNull();
        assertThat(state.getDeterrentFullCommandId()).isNotBlank();
    }

    @Test
    void keepsExistingSessionAcrossLongPositiveGapAfterFullDeterrentMarker() {
        process("event-full-session-0", 0, true);
        process("event-full-session-2", 2, true);
        process("event-full-session-5", 5, true);

        AnimalObservationResult afterGap = process("event-full-session-9", 9, true);

        assertThat(afterGap.trigger()).isEqualTo(ObservationTrigger.NONE);
        assertThat(afterGap.commandDecision().outcome()).isEqualTo(CommandOutcome.NOT_REQUESTED);
        AnimalObservationState state = observationRepository.findByCameraId("cam-001").orElseThrow();
        assertThat(state.getFirstDetectedAt()).isEqualTo(BASE);
        assertThat(state.getLastDetectedAt()).isEqualTo(BASE.plusSeconds(9));
        assertThat(state.getDeterrentFullCommandId()).isNotBlank();
        assertThat(commandRepository.count()).isEqualTo(1);
    }

    @Test
    void prioritizesPersistentResponseAfterSuppressedSoundAndRetriesSuppressedStop() {
        transportReadiness.setReady(false);
        assertThat(process("event-blocked-sound-0", 0, true).commandDecision().outcome())
                .isEqualTo(CommandOutcome.SUPPRESSED);
        process("event-blocked-sound-2", 2, true);

        transportReadiness.setReady(true);
        AnimalObservationResult persistent = process("event-persistent-5", 5, true);
        assertThat(persistent.commandType()).isEqualTo(DeviceCommandType.DETERRENT_FULL);
        assertThat(persistent.commandDecision().outcome()).isEqualTo(CommandOutcome.CREATED);

        transportReadiness.setReady(false);
        process("event-empty-6", 6, false);
        AnimalObservationResult blockedStop = process("event-empty-8", 8, false);
        assertThat(blockedStop.commandType()).isEqualTo(DeviceCommandType.STOP_DETERRENT);
        assertThat(blockedStop.commandDecision().outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.PRESENT);

        transportReadiness.setReady(true);
        AnimalObservationResult retriedStop = process("event-empty-9", 9, false);
        assertThat(retriedStop.commandDecision().outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(observationRepository.findByCameraId("cam-001").orElseThrow().getPresenceState())
                .isEqualTo(AnimalPresenceState.IDLE);
    }

    @Test
    void holdsObservationGateUntilTransactionCompletionAndCreatesOneFirstCommand() throws Exception {
        Instant capturedAt = BASE.plusSeconds(20);
        clock.set(capturedAt);
        DetectionEvent firstEvent = saveEvent("event-concurrent-first", capturedAt);
        DetectionEvent secondEvent = saveEvent("event-concurrent-second", capturedAt);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstServiceReturned = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);

        try {
            Future<AnimalObservationResult> first = executor.submit(() -> transactionTemplate.execute(status -> {
                AnimalObservationResult result = service.process(firstEvent, "cam-001", capturedAt, true);
                firstServiceReturned.countDown();
                await(allowFirstCommit);
                return result;
            }));
            assertThat(firstServiceReturned.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AnimalObservationResult> second = executor.submit(() -> transactionTemplate.execute(status -> {
                secondTransactionStarted.countDown();
                return service.process(secondEvent, "cam-001", capturedAt, true);
            }));
            assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            allowFirstCommit.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).commandDecision().outcome())
                    .isEqualTo(CommandOutcome.CREATED);
            assertThat(second.get(10, TimeUnit.SECONDS).trigger())
                    .isEqualTo(ObservationTrigger.STALE_EVENT_IGNORED);
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
        }

        assertThat(commandRepository.count()).isEqualTo(1);
        assertThat(observationRepository.count()).isEqualTo(1);
    }

    private AnimalObservationResult process(String eventId, long offsetSeconds, boolean animalPresent) {
        Instant capturedAt = BASE.plusSeconds(offsetSeconds);
        clock.set(capturedAt);
        return transactionTemplate.execute(status -> {
            DetectionEvent event = eventRepository.save(new DetectionEvent(
                    eventId,
                    "cam-001",
                    capturedAt,
                    1280,
                    720,
                    "animal-detector-v1",
                    null
            ));
            return service.process(event, "cam-001", capturedAt, animalPresent);
        });
    }

    private DetectionEvent saveEvent(String eventId, Instant capturedAt) {
        return transactionTemplate.execute(status -> eventRepository.saveAndFlush(new DetectionEvent(
                eventId,
                "cam-001",
                capturedAt,
                1280,
                720,
                "animal-detector-v1",
                null
        )));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("transaction completion was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during concurrency test", exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock observationMutableClock() {
            return new MutableClock(BASE);
        }

        @Bean
        MutableTransportReadiness observationTransportReadiness() {
            return new MutableTransportReadiness();
        }
    }

    static final class MutableTransportReadiness implements ActuationTransportReadiness {
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
            current = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            current.set(instant);
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
