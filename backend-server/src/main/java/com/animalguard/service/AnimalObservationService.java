package com.animalguard.service;

import com.animalguard.config.ObservationProperties;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnimalObservationService {

    private static final String FIRST_DETECTION_REASON = "FIRST_ANIMAL_DETECTION";
    private static final String PERSISTENCE_REASON = "PERSISTENT_ANIMAL_DETECTION";
    private static final String DISAPPEARANCE_REASON = "ANIMAL_DISAPPEARED";
    private static final String NO_EVENT_TIMEOUT_REASON = "NO_EVENT_TIMEOUT";
    private static final EnumSet<DeviceCommandStatus> CLEARABLE_TERMINAL_STATUSES = EnumSet.of(
            DeviceCommandStatus.FAILED,
            DeviceCommandStatus.EXPIRED
    );

    private final AnimalObservationStateRepository observationRepository;
    private final DeviceCommandRepository commandRepository;
    private final DetectionEventRepository eventRepository;
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
        return insideObservationGate(() -> processInsideGate(event, cameraId, capturedAt, animalPresent));
    }

    @Transactional
    public boolean reconcileTerminalCommandMarkers(Long observationId, Instant now) {
        return insideObservationGate(() -> reconcileTerminalCommandMarkersInsideGate(observationId, now));
    }

    @Transactional
    public CommandDecision handleNoEventTimeout(Long observationId, Instant now) {
        return insideObservationGate(() -> handleNoEventTimeoutInsideGate(observationId, now));
    }

    @Transactional
    public boolean releaseExecutedDeterrentForRepeat(Long observationId, Instant now) {
        return insideObservationGate(() -> releaseExecutedDeterrentForRepeatInsideGate(observationId, now));
    }

    private <T> T insideObservationGate(Supplier<T> action) {
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
            return action.get();
        } finally {
            if (releaseOnReturn) {
                observationGate.unlock();
            }
        }
    }

    private boolean reconcileTerminalCommandMarkersInsideGate(Long observationId, Instant now) {
        AnimalObservationState state = observationRepository.findById(observationId).orElse(null);
        if (state == null) {
            return false;
        }

        boolean changed = clearTerminalMarker(
                state,
                state.getSoundAlertCommandId(),
                DeviceCommandType.SOUND_ALERT,
                now
        );
        changed |= clearTerminalMarker(
                state,
                state.getDeterrentFullCommandId(),
                DeviceCommandType.DETERRENT_FULL,
                now
        );
        return changed;
    }

    private boolean clearTerminalMarker(
            AnimalObservationState state,
            String markerCommandId,
            DeviceCommandType expectedType,
            Instant now
    ) {
        if (markerCommandId == null) {
            return false;
        }
        Optional<DeviceCommand> found = commandRepository.findByCommandId(markerCommandId);
        if (found.isEmpty()) {
            log.error(
                    "Observation marker references an unknown command: cameraId={}, commandId={}, commandType={}",
                    state.getCameraId(),
                    markerCommandId,
                    expectedType
            );
            return false;
        }

        DeviceCommand command = found.orElseThrow();
        if (command.getCommandType() != expectedType
                || !CLEARABLE_TERMINAL_STATUSES.contains(command.getStatus())) {
            return false;
        }
        boolean cleared = switch (expectedType) {
            case SOUND_ALERT -> state.clearSoundAlertCommand(markerCommandId, now);
            case DETERRENT_FULL -> state.clearDeterrentFullCommand(markerCommandId, now);
            case STOP_DETERRENT, ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT -> false;
        };
        if (cleared) {
            log.info(
                    "Terminal observation marker cleared: cameraId={}, commandId={}, commandType={}, commandStatus={}",
                    state.getCameraId(),
                    markerCommandId,
                    expectedType,
                    command.getStatus()
            );
        }
        return cleared;
    }

    private CommandDecision handleNoEventTimeoutInsideGate(Long observationId, Instant now) {
        AnimalObservationState state = observationRepository.findById(observationId).orElse(null);
        if (state == null
                || state.getPresenceState() != AnimalPresenceState.PRESENT
                || now.isBefore(state.getUpdatedAt().plus(properties.noEventTimeout()))) {
            return CommandDecision.notRequested();
        }

        String deterrentCommandId = state.getDeterrentFullCommandId();
        if (deterrentCommandId == null) {
            state.resetToIdleWithoutEvent(now);
            log.info("Observation ended after no-event timeout without STOP: cameraId={}", state.getCameraId());
            return CommandDecision.notRequested();
        }

        Optional<DeviceCommand> found = commandRepository.findByCommandId(deterrentCommandId);
        if (found.isEmpty()) {
            log.error(
                    "No-event watchdog cannot resolve deterrent marker command: cameraId={}, commandId={}",
                    state.getCameraId(),
                    deterrentCommandId
            );
            return CommandDecision.notRequested();
        }

        DeviceCommand deterrentCommand = found.orElseThrow();
        DetectionEvent origin = deterrentCommand.getEvent();
        if (deterrentCommand.getSource() != DeviceCommandSource.AUTOMATIC
                || deterrentCommand.getCommandType() != DeviceCommandType.DETERRENT_FULL
                || origin == null
                || !state.getCameraId().equals(origin.getCameraId())) {
            log.error(
                    "No-event watchdog rejected invalid deterrent marker origin: cameraId={}, commandId={}",
                    state.getCameraId(),
                    deterrentCommandId
            );
            return CommandDecision.notRequested();
        }

        CommandDecision decision = commandCreationService.createAutomaticForSessionIfAllowed(
                origin,
                state.getCameraId(),
                DeviceCommandType.STOP_DETERRENT,
                null,
                NO_EVENT_TIMEOUT_REASON,
                state.getFirstDetectedAt()
        );
        if (decision.outcome() == CommandOutcome.CREATED) {
            state.resetToIdleWithoutEvent(now);
        }
        log.info(
                "No-event watchdog decision: cameraId={}, originEventId={}, outcome={}, commandId={}, blockers={}",
                state.getCameraId(),
                origin.getEventId(),
                decision.outcome(),
                decision.commandId(),
                decision.blockers()
        );
        return decision;
    }

    private boolean releaseExecutedDeterrentForRepeatInsideGate(Long observationId, Instant now) {
        if (properties.deterrentRepeatInterval().isZero()) {
            return false;
        }

        AnimalObservationState state = observationRepository.findById(observationId).orElse(null);
        if (state == null
                || state.getPresenceState() != AnimalPresenceState.PRESENT
                || state.getDeterrentFullCommandId() == null
                || state.getAbsenceStartedAt() != null
                || !state.getLastProcessedCapturedAt().equals(state.getLastDetectedAt())) {
            return false;
        }

        String markerCommandId = state.getDeterrentFullCommandId();
        Optional<DeviceCommand> found = commandRepository.findByCommandId(markerCommandId);
        if (found.isEmpty()) {
            return false;
        }
        DeviceCommand command = found.orElseThrow();
        if (command.getSource() != DeviceCommandSource.AUTOMATIC
                || command.getCommandType() != DeviceCommandType.DETERRENT_FULL
                || command.getStatus() != DeviceCommandStatus.EXECUTED
                || command.getExecutedAt() == null
                || now.isBefore(command.getExecutedAt().plus(properties.deterrentRepeatInterval()))) {
            return false;
        }

        if (eventRepository.findLatestPositiveAt(
                state.getCameraId(),
                state.getLastDetectedAt(),
                PageRequest.of(0, 1)
        ).isEmpty()) {
            return false;
        }

        boolean cleared = state.clearDeterrentFullCommand(markerCommandId, now);
        if (cleared) {
            log.info(
                    "Executed deterrent marker released for positive-event reevaluation: cameraId={}, commandId={}",
                    state.getCameraId(),
                    markerCommandId
            );
        }
        return cleared;
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
                    DeviceCommandType.DETERRENT_FULL,
                    properties.deterrentFullDurationMs(),
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
                    DeviceCommandType.DETERRENT_FULL,
                    properties.deterrentFullDurationMs(),
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
                    DeviceCommandType.DETERRENT_FULL,
                    properties.deterrentFullDurationMs(),
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
                    DeviceCommandType.DETERRENT_FULL,
                    properties.deterrentFullDurationMs(),
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
        CommandDecision decision = commandCreationService.createAutomaticForSessionIfAllowed(
                event,
                cameraId,
                commandType,
                durationMs,
                reason,
                state.getFirstDetectedAt()
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
