package com.animalguard.service;

import com.animalguard.config.DeviceControlProperties;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandCreationService {

    private static final int COMMAND_DURATION_MS = 5_000;

    private final DeviceCommandRepository deviceCommandRepository;
    private final DeviceControlProperties properties;
    private final ActuationPreflightService preflightService;
    private final Clock clock;
    private final ReentrantLock commandGate = new ReentrantLock(true);

    public CommandDecision createIfAllowed(
            DetectionEvent event,
            String cameraId,
            DeviceCommandType commandType,
            String reason
    ) {
        Integer durationMs = durationForAutomaticCommand(commandType);
        ActuationPreflight preflight = preflightService.evaluate();
        if (!preflight.ready()) {
            return CommandDecision.suppressed(preflight.blockers());
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

            Instant now = clock.instant();
            Optional<DeviceCommand> latestCommand =
                    deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc(deviceId);
            CommandGateState state = stateOf(latestCommand, now);
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

    private Integer durationForAutomaticCommand(DeviceCommandType commandType) {
        return switch (Objects.requireNonNull(commandType, "commandType must not be null")) {
            case SOUND_ALERT, DETERRENT_FULL -> COMMAND_DURATION_MS;
            case STOP_DETERRENT -> null;
            case ROTATE_CAMERA_LEFT, ROTATE_CAMERA_RIGHT -> throw new IllegalArgumentException(
                    "AUTOMATIC command does not allow commandType " + commandType
            );
        };
    }

    private boolean isTransactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }

    private CommandGateState stateOf(Optional<DeviceCommand> latestCommand, Instant now) {
        if (latestCommand.isEmpty()) {
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
