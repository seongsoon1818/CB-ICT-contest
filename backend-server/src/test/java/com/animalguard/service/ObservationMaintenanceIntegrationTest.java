package com.animalguard.service;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.AnimalDetection;
import com.animalguard.domain.AnimalObservationState;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.AnimalObservationStateRepository;
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
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "animalguard.actuation.enabled=true",
        "animalguard.actuation.risk-policy-confirmed=true",
        "animalguard.device-control.cooldown=10s",
        "animalguard.observation.deterrent-repeat-interval=5s"
})
@ActiveProfiles("test")
@Import(ObservationMaintenanceIntegrationTest.TestBeans.class)
class ObservationMaintenanceIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-08-26T00:00:00Z");

    @Autowired
    private AnimalObservationService service;
    @Autowired
    private CommandReconciliationScheduler scheduler;
    @Autowired
    private AnimalObservationStateRepository observationRepository;
    @Autowired
    private DeviceCommandRepository commandRepository;
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
        eventRepository.deleteAll();
    }

    @Test
    void clearsFailedMarkerButKeepsExecutedMarker() {
        process("event-sound", 0, true);
        AnimalObservationState state = state();
        String soundCommandId = state.getSoundAlertCommandId();
        transition(soundCommandId, DeviceCommandStatus.FAILED, 1);

        assertThat(service.reconcileTerminalCommandMarkers(state.getId(), BASE.plusSeconds(2))).isTrue();
        assertThat(state().getSoundAlertCommandId()).isNull();

        process("event-continuity", 2, true);
        process("event-deterrent", 5, true);
        state = state();
        String deterrentCommandId = state.getDeterrentFullCommandId();
        transition(deterrentCommandId, DeviceCommandStatus.EXECUTED, 7);

        assertThat(service.reconcileTerminalCommandMarkers(state.getId(), BASE.plusSeconds(8))).isFalse();
        assertThat(state().getDeterrentFullCommandId()).isEqualTo(deterrentCommandId);
    }

    @Test
    void noEventTimeoutWithoutDeterrentEndsSessionWithoutFakeEventOrStop() {
        process("event-no-deterrent", 0, true);
        AnimalObservationState before = state();

        CommandDecision decision = service.handleNoEventTimeout(before.getId(), BASE.plusSeconds(10));

        AnimalObservationState after = state();
        assertThat(decision.outcome()).isEqualTo(CommandOutcome.NOT_REQUESTED);
        assertThat(after.getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        assertThat(after.getLastProcessedCapturedAt()).isEqualTo(BASE);
        assertThat(after.getUpdatedAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(commandRepository.findAll()).extracting(DeviceCommand::getCommandType)
                .containsExactly(DeviceCommandType.SOUND_ALERT);
    }

    @Test
    void noEventTimeoutUsesDeterrentOriginForStopAndEndsOnlyAfterCreation() {
        process("event-first", 0, true);
        process("event-continuity", 2, true);
        process("event-deterrent-origin", 5, true);
        AnimalObservationState before = state();
        String deterrentCommandId = before.getDeterrentFullCommandId();

        clock.set(BASE.plusSeconds(15));
        CommandDecision decision = service.handleNoEventTimeout(before.getId(), clock.instant());

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(state().getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        transactionTemplate.executeWithoutResult(status -> {
            DeviceCommand stop = commandRepository.findByCommandId(decision.commandId()).orElseThrow();
            DeviceCommand deterrent = commandRepository.findByCommandId(deterrentCommandId).orElseThrow();
            assertThat(stop.getCommandType()).isEqualTo(DeviceCommandType.STOP_DETERRENT);
            assertThat(stop.getReason()).isEqualTo("NO_EVENT_TIMEOUT");
            assertThat(stop.getEvent().getEventId()).isEqualTo(deterrent.getEvent().getEventId());
            assertThat(stop.getEvent().getEventId()).isEqualTo("event-deterrent-origin");
        });
    }

    @Test
    void suppressedNoEventStopKeepsPresentForLaterRetry() {
        process("event-first", 0, true);
        process("event-continuity", 2, true);
        process("event-deterrent", 5, true);
        AnimalObservationState before = state();
        transportReadiness.setReady(false);
        clock.set(BASE.plusSeconds(15));

        CommandDecision decision = service.handleNoEventTimeout(before.getId(), clock.instant());

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        assertThat(state().getPresenceState()).isEqualTo(AnimalPresenceState.PRESENT);
        assertThat(commandRepository.count()).isEqualTo(2);
    }

    @Test
    void releasesOnlyElapsedExecutedDeterrentWithLatestPositiveEventThenReevaluates() {
        process("event-first", 0, true);
        process("event-continuity", 2, true);
        process("event-deterrent", 5, true);
        AnimalObservationState before = state();
        String firstDeterrentId = before.getDeterrentFullCommandId();
        transition(firstDeterrentId, DeviceCommandStatus.EXECUTED, 7);
        process("event-still-present-8", 8, true);
        process("event-still-present-10", 10, true);

        assertThat(service.releaseExecutedDeterrentForRepeat(before.getId(), BASE.plusSeconds(11))).isFalse();
        process("event-still-present-12", 12, true);
        scheduler.reconcile();
        assertThat(state().getDeterrentFullCommandId()).isNull();

        AnimalObservationResult cooldownSuppressed = process("event-repeat-cooldown", 13, true);
        AnimalObservationResult reevaluated = process("event-repeat", 15, true);

        assertThat(cooldownSuppressed.commandType()).isEqualTo(DeviceCommandType.DETERRENT_FULL);
        assertThat(cooldownSuppressed.commandDecision().outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(reevaluated.commandType()).isEqualTo(DeviceCommandType.DETERRENT_FULL);
        assertThat(reevaluated.commandDecision().outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(commandRepository.findAll()).extracting(DeviceCommand::getCommandType)
                .containsExactly(
                        DeviceCommandType.SOUND_ALERT,
                        DeviceCommandType.DETERRENT_FULL,
                        DeviceCommandType.DETERRENT_FULL
                );
    }

    @Test
    void duplicateScansExpireOnceThenClearMarkerBeforeNoEventReset() {
        process("event-expiring-sound", 0, true);
        AnimalObservationState before = state();
        String soundCommandId = before.getSoundAlertCommandId();

        clock.set(BASE.plusSeconds(10));
        scheduler.reconcile();

        DeviceCommand expired = commandRepository.findByCommandId(soundCommandId).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(DeviceCommandStatus.EXPIRED);
        assertThat(expired.getExpiredAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(state().getSoundAlertCommandId()).isNull();
        assertThat(state().getPresenceState()).isEqualTo(AnimalPresenceState.PRESENT);

        clock.set(BASE.plusSeconds(20));
        scheduler.reconcile();
        scheduler.reconcile();

        DeviceCommand afterDuplicateScan = commandRepository.findByCommandId(soundCommandId).orElseThrow();
        assertThat(afterDuplicateScan.getExpiredAt()).isEqualTo(BASE.plusSeconds(10));
        assertThat(state().getPresenceState()).isEqualTo(AnimalPresenceState.IDLE);
        assertThat(commandRepository.count()).isEqualTo(1);
    }

    @Test
    void schedulerAppliesCommandTimeoutsAtBoundaryAndLeavesFreshCandidatesUntouched() {
        saveCommand("created-boundary", DeviceCommandStatus.CREATED, BASE, BASE.plusSeconds(30));
        saveCommand("created-fresh", DeviceCommandStatus.CREATED, BASE, BASE.plusSeconds(31));
        saveCommand("published-boundary", DeviceCommandStatus.PUBLISHED, BASE.plusSeconds(15), BASE.plusSeconds(60));
        saveCommand("published-fresh", DeviceCommandStatus.PUBLISHED, BASE.plusSeconds(16), BASE.plusSeconds(60));
        saveCommand("ack-boundary", DeviceCommandStatus.ACKNOWLEDGED, BASE.plusSeconds(15), BASE.plusSeconds(60));
        saveCommand("ack-fresh", DeviceCommandStatus.ACKNOWLEDGED, BASE.plusSeconds(16), BASE.plusSeconds(60));
        clock.set(BASE.plusSeconds(30));

        scheduler.reconcile();

        assertThat(status("created-boundary")).isEqualTo(DeviceCommandStatus.EXPIRED);
        assertThat(status("created-fresh")).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(status("published-boundary")).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(status("published-fresh")).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(status("ack-boundary")).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(status("ack-fresh")).isEqualTo(DeviceCommandStatus.ACKNOWLEDGED);

        scheduler.reconcile();

        assertThat(commandRepository.findByCommandId("created-boundary").orElseThrow().getExpiredAt())
                .isEqualTo(BASE.plusSeconds(30));
        assertThat(commandRepository.findByCommandId("published-boundary").orElseThrow().getFailedAt())
                .isEqualTo(BASE.plusSeconds(30));
        assertThat(commandRepository.findByCommandId("ack-boundary").orElseThrow().getFailedAt())
                .isEqualTo(BASE.plusSeconds(30));
    }

    @Test
    void doesNotReleaseRepeatMarkerAfterARealEmptyEvent() {
        process("event-first", 0, true);
        process("event-continuity", 2, true);
        process("event-deterrent", 5, true);
        String deterrentCommandId = state().getDeterrentFullCommandId();
        process("event-empty", 6, false);
        transition(deterrentCommandId, DeviceCommandStatus.EXECUTED, 7);

        assertThat(service.releaseExecutedDeterrentForRepeat(state().getId(), BASE.plusSeconds(12))).isFalse();
        assertThat(state().getDeterrentFullCommandId()).isEqualTo(deterrentCommandId);
    }

    private AnimalObservationResult process(String eventId, long offsetSeconds, boolean animalPresent) {
        Instant capturedAt = BASE.plusSeconds(offsetSeconds);
        clock.set(capturedAt);
        return transactionTemplate.execute(status -> {
            DetectionEvent event = new DetectionEvent(
                    eventId,
                    "cam-001",
                    capturedAt,
                    1280,
                    720,
                    "animal-detector-v1",
                    null
            );
            if (animalPresent) {
                event.addDetection(new AnimalDetection(
                        "detection-" + eventId,
                        null,
                        "MAGPIE",
                        0.95,
                        null,
                        10,
                        10,
                        100,
                        100
                ));
            }
            eventRepository.save(event);
            return service.process(event, "cam-001", capturedAt, animalPresent);
        });
    }

    private void transition(String commandId, DeviceCommandStatus target, long terminalOffsetSeconds) {
        transactionTemplate.executeWithoutResult(status -> {
            DeviceCommand command = commandRepository.findByCommandId(commandId).orElseThrow();
            Instant publishedAt = BASE.plusSeconds(Math.max(0, terminalOffsetSeconds - 2));
            command.markPublished(publishedAt);
            if (target == DeviceCommandStatus.FAILED) {
                command.markFailed(BASE.plusSeconds(terminalOffsetSeconds), null);
                return;
            }
            command.markAcknowledged(BASE.plusSeconds(terminalOffsetSeconds - 1), BASE);
            command.markExecuted(BASE.plusSeconds(terminalOffsetSeconds), BASE);
        });
    }

    private void saveCommand(
            String commandId,
            DeviceCommandStatus status,
            Instant transitionAt,
            Instant expiresAt
    ) {
        transactionTemplate.executeWithoutResult(transactionStatus -> {
            DetectionEvent event = eventRepository.save(new DetectionEvent(
                    "event-" + commandId,
                    "cam-001",
                    BASE,
                    1280,
                    720,
                    "animal-detector-v1",
                    null
            ));
            DeviceCommand command = new DeviceCommand(
                    commandId,
                    event,
                    "pi-001",
                    DeviceCommandSource.AUTOMATIC,
                    DeviceCommandType.SOUND_ALERT,
                    2_000,
                    "SCHEDULER_BOUNDARY_TEST",
                    BASE,
                    expiresAt
            );
            if (status == DeviceCommandStatus.PUBLISHED) {
                command.markPublished(transitionAt);
            } else if (status == DeviceCommandStatus.ACKNOWLEDGED) {
                command.markPublished(BASE);
                command.markAcknowledged(transitionAt, BASE);
            }
            commandRepository.save(command);
        });
    }

    private DeviceCommandStatus status(String commandId) {
        return commandRepository.findByCommandId(commandId).orElseThrow().getStatus();
    }

    private AnimalObservationState state() {
        return observationRepository.findByCameraId("cam-001").orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {
        @Bean
        @Primary
        MutableClock maintenanceMutableClock() {
            return new MutableClock(BASE);
        }

        @Bean
        MutableTransportReadiness maintenanceTransportReadiness() {
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
