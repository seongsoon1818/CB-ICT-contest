package com.animalguard.service;

import com.animalguard.config.DeviceControlProperties;
import com.animalguard.config.ReconciliationProperties;
import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandCreationService {

    private final DeviceCommandRepository deviceCommandRepository;
    private final DeviceControlProperties properties;
    private final ActuationPreflightService preflightService;
    private final ReconciliationProperties reconciliationProperties;
    private final Clock clock;
    private final ReentrantLock commandGate = new ReentrantLock(true);

    public CommandDecision createAutomaticIfAllowed(
            DetectionEvent event,
            String cameraId,
            DeviceCommandType commandType,
            Integer durationMs,
            String reason
    ) {
        return createAutomaticIfAllowed(
                event,
                cameraId,
                commandType,
                durationMs,
                reason,
                null
        );
    }

    public CommandDecision createAutomaticForSessionIfAllowed(
            DetectionEvent event,
            String cameraId,
            DeviceCommandType commandType,
            Integer durationMs,
            String reason,
            Instant sessionFirstDetectedAt
    ) {
        return createAutomaticIfAllowed(
                event,
                cameraId,
                commandType,
                durationMs,
                reason,
                java.util.Objects.requireNonNull(
                        sessionFirstDetectedAt,
                        "sessionFirstDetectedAt must not be null"
                )
        );
    }

    private CommandDecision createAutomaticIfAllowed(
            DetectionEvent event,
            String cameraId,
            DeviceCommandType commandType,
            Integer durationMs,
            String reason,
            Instant sessionFirstDetectedAt
    ) {
        List<ActuationBlocker> globalBlockers = preflightService.blockersForAutomaticCommand(commandType);
        if (!globalBlockers.isEmpty()) {
            return CommandDecision.suppressed(globalBlockers);
        }

        String deviceId = properties.cameraDeviceMappings().get(cameraId);
        if (deviceId == null) {
            return CommandDecision.suppressed(List.of(ActuationBlocker.CAMERA_UNMAPPED));
        }
        commandGate.lock();
        boolean releaseOnReturn = true;
        try {
            if (isTransactionSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        commandGate.unlock();
                    }
                });
                releaseOnReturn = false;
            }

            if (sessionFirstDetectedAt != null
                    && deviceCommandRepository.countAutomaticAttemptsInObservationSession(
                    cameraId,
                    DeviceCommandSource.AUTOMATIC,
                    commandType,
                    sessionFirstDetectedAt
            ) >= reconciliationProperties.maxAutomaticAttemptsPerSession()) {
                return CommandDecision.suppressed(List.of(ActuationBlocker.AUTOMATIC_RETRY_EXHAUSTED));
            }

            Instant now = clock.instant();
            Optional<DeviceCommand> latestCommand =
                    deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                            deviceId,
                            DeviceCommandSource.AUTOMATIC
                    );
            CommandGateState state = stateOf(latestCommand, commandType, now);
            if (state == CommandGateState.COOLDOWN) {
                DeviceCommand latest = latestCommand.orElseThrow();
                log.info(
                        "Device command suppressed during cooldown: deviceId={}, latestCommandId={}, cooldownEndsAt={}",
                        deviceId,
                        latest.getCommandId(),
                        latest.getCreatedAt().plus(properties.cooldown())
                );
                return CommandDecision.suppressed(List.of(ActuationBlocker.COOLDOWN_ACTIVE));
            }

            DeviceCommand command = deviceCommandRepository.save(new DeviceCommand(
                    "command-" + UUID.randomUUID(),
                    event,
                    deviceId,
                    DeviceCommandSource.AUTOMATIC,
                    commandType,
                    durationMs,
                    reason,
                    now,
                    now.plus(properties.commandTtl())
            ));
            return CommandDecision.created(command.getCommandId());
        } finally {
            if (releaseOnReturn) {
                commandGate.unlock();
            }
        }
    }

    private boolean isTransactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private CommandGateState stateOf(
            Optional<DeviceCommand> latestCommand,
            DeviceCommandType requestedCommandType,
            Instant now
    ) {
        if (requestedCommandType == DeviceCommandType.STOP_DETERRENT
                || latestCommand.isEmpty()
                || latestCommand.orElseThrow().getCommandType() != requestedCommandType) {
            return CommandGateState.IDLE;
        }

        Instant cooldownEndsAt = latestCommand.orElseThrow().getCreatedAt().plus(properties.cooldown());
        return cooldownEndsAt.isAfter(now) ? CommandGateState.COOLDOWN : CommandGateState.IDLE;
    }

    private enum CommandGateState {
        IDLE,
        COOLDOWN
    }
}
