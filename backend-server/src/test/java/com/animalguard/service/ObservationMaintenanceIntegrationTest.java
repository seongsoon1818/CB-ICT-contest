package com.animalguard.service;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.AnimalDetection;
import com.animalguard.domain.AnimalObservationState;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
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
        assertThat(service.releaseExecutedDeterrentForRepeat(before.getId(), BASE.plusSeconds(12))).isTrue();
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
