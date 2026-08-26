package com.animalguard.service;

import com.animalguard.config.ObservationProperties;
import com.animalguard.domain.AnimalObservationState;
import com.animalguard.domain.AnimalPresenceState;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.AnimalObservationStateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
public class AnimalObservationService {

    private static final String FIRST_DETECTION_REASON = "FIRST_ANIMAL_DETECTION";
    private static final String PERSISTENCE_REASON = "PERSISTENT_ANIMAL_DETECTION";
    private static final String DISAPPEARANCE_REASON = "ANIMAL_DISAPPEARED";

    private final AnimalObservationStateRepository observationRepository;
    private final DeviceCommandCreationService commandCreationService;
    private final ObservationProperties properties;
    private final Clock clock;
    private final ReentrantLock observationGate = new ReentrantLock(true);

    @Transactional
    public AnimalObservationResult process(
            DetectionEvent event,
            String cameraId,
            Instant capturedAt,
            boolean animalPresent
    ) {
        observationGate.lock();
        boolean releaseOnReturn = true;
        try {
            if (isTransactionSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        observationGate.unlock();
                    }
                });
                releaseOnReturn = false;
            }
            return processInsideGate(event, cameraId, capturedAt, animalPresent);
        } finally {
            if (releaseOnReturn) {
                observationGate.unlock();
            }
        }
    }

    private AnimalObservationResult processInsideGate(
            DetectionEvent event,
            String cameraId,
            Instant capturedAt,
            boolean animalPresent
    ) {
        Instant now = clock.instant();
        AnimalObservationState state = observationRepository.findByCameraId(cameraId).orElse(null);
        if (state != null && !capturedAt.isAfter(state.getLastProcessedCapturedAt())) {
            return result(state, ObservationTrigger.STALE_EVENT_IGNORED, null, CommandDecision.notRequested());
        }

        if (state == null) {
            state = animalPresent
                    ? AnimalObservationState.initializePresent(cameraId, capturedAt, now)
                    : AnimalObservationState.initializeIdle(cameraId, capturedAt, now);
            observationRepository.save(state);
            if (!animalPresent) {
                return result(state, ObservationTrigger.NONE, null, CommandDecision.notRequested());
            }
            return requestAndRecord(
                    state,
                    event,
                    cameraId,
                    DeviceCommandType.SOUND_ALERT,
                    properties.soundAlertDurationMs(),
                    FIRST_DETECTION_REASON,
                    ObservationTrigger.FIRST_DETECTION,
                    now
            );
        }

        if (state.getPresenceState() == AnimalPresenceState.IDLE) {
            if (!animalPresent) {
                state.markIdleProcessed(capturedAt, now);
                return result(state, ObservationTrigger.NONE, null, CommandDecision.notRequested());
            }
            state.startPresence(capturedAt, now);
            return requestAndRecord(
                    state,
                    event,
                    cameraId,
                    DeviceCommandType.SOUND_ALERT,
                    properties.soundAlertDurationMs(),
                    FIRST_DETECTION_REASON,
                    ObservationTrigger.FIRST_DETECTION,
                    now
            );
        }

        if (animalPresent) {
            return processPositive(state, event, cameraId, capturedAt, now);
        }
        return processEmpty(state, event, cameraId, capturedAt, now);
    }

    private AnimalObservationResult processPositive(
            AnimalObservationState state,
            DetectionEvent event,
            String cameraId,
            Instant capturedAt,
            Instant now
    ) {
        Duration gap = Duration.between(state.getLastProcessedCapturedAt(), capturedAt);
        if (gap.compareTo(properties.continuityTimeout()) > 0
                && state.getDeterrentFullCommandId() == null) {
            state.restartPresence(capturedAt, now);
            AnimalObservationResult commandResult = requestAndRecord(
                    state,
                    event,
                    cameraId,
                    DeviceCommandType.SOUND_ALERT,
                    properties.soundAlertDurationMs(),
                    FIRST_DETECTION_REASON,
                    ObservationTrigger.CONTINUITY_RESTARTED,
                    now
            );
            return commandResult;
        }

        state.recordPresent(capturedAt, now);
        Duration presenceDuration = Duration.between(state.getFirstDetectedAt(), capturedAt);
        if (presenceDuration.compareTo(properties.persistenceThreshold()) >= 0
                && state.getDeterrentFullCommandId() == null) {
            return requestAndRecord(
                    state,
                    event,
                    cameraId,
                    DeviceCommandType.DETERRENT_FULL,
                    properties.deterrentFullDurationMs(),
                    PERSISTENCE_REASON,
                    ObservationTrigger.PERSISTENCE_REACHED,
                    now
            );
        }
        if (state.getSoundAlertCommandId() == null && state.getDeterrentFullCommandId() == null) {
            return requestAndRecord(
                    state,
                    event,
                    cameraId,
                    DeviceCommandType.SOUND_ALERT,
                    properties.soundAlertDurationMs(),
                    FIRST_DETECTION_REASON,
                    ObservationTrigger.FIRST_DETECTION,
                    now
            );
        }
        return result(state, ObservationTrigger.NONE, null, CommandDecision.notRequested());
    }

    private AnimalObservationResult processEmpty(
            AnimalObservationState state,
            DetectionEvent event,
            String cameraId,
            Instant capturedAt,
            Instant now
    ) {
        Instant absenceStartedAt = state.getAbsenceStartedAt();
        state.startAbsence(capturedAt, now);
        if (absenceStartedAt == null
                || Duration.between(absenceStartedAt, capturedAt).compareTo(properties.absenceGrace()) < 0) {
            return result(state, ObservationTrigger.NONE, null, CommandDecision.notRequested());
        }

        if (state.getDeterrentFullCommandId() == null) {
            state.resetToIdle(capturedAt, now);
            return result(
                    state,
                    ObservationTrigger.DISAPPEARANCE_CONFIRMED,
                    null,
                    CommandDecision.notRequested()
            );
        }

        AnimalObservationResult stopResult = requestAndRecord(
                state,
                event,
                cameraId,
                DeviceCommandType.STOP_DETERRENT,
                null,
                DISAPPEARANCE_REASON,
                ObservationTrigger.DISAPPEARANCE_CONFIRMED,
                now
        );
        if (stopResult.commandDecision().outcome() == CommandOutcome.CREATED) {
            state.resetToIdle(capturedAt, now);
            return result(
                    state,
                    ObservationTrigger.DISAPPEARANCE_CONFIRMED,
                    DeviceCommandType.STOP_DETERRENT,
                    stopResult.commandDecision()
            );
        }
        return stopResult;
    }

    private AnimalObservationResult requestAndRecord(
            AnimalObservationState state,
            DetectionEvent event,
            String cameraId,
            DeviceCommandType commandType,
            Integer durationMs,
            String reason,
            ObservationTrigger trigger,
            Instant now
    ) {
        CommandDecision decision = commandCreationService.createAutomaticIfAllowed(
                event,
                cameraId,
                commandType,
                durationMs,
                reason
        );
        if (decision.outcome() == CommandOutcome.NOT_REQUESTED) {
            throw new IllegalStateException("automatic command intent must produce CREATED or SUPPRESSED");
        }
        if (decision.outcome() == CommandOutcome.CREATED) {
            switch (commandType) {
                case SOUND_ALERT -> state.recordSoundAlertCommand(decision.commandId(), now);
                case DETERRENT_FULL -> state.recordDeterrentFullCommand(decision.commandId(), now);
                case STOP_DETERRENT -> {
                    // The caller resets the observation only after the created STOP result is visible.
                }
                case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT -> throw new IllegalStateException(
                        "observation policy selected a manual-only command"
                );
            }
        }
        return result(state, trigger, commandType, decision);
    }

    private AnimalObservationResult result(
            AnimalObservationState state,
            ObservationTrigger trigger,
            DeviceCommandType commandType,
            CommandDecision decision
    ) {
        return new AnimalObservationResult(state.getPresenceState(), trigger, commandType, decision);
    }

    private boolean isTransactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }
}
