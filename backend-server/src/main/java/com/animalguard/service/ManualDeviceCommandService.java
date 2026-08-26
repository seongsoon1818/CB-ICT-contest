package com.animalguard.service;

import com.animalguard.config.DeviceControlProperties;
import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.exception.ManualCommandConflictException;
import com.animalguard.exception.UnknownDeviceException;
import com.animalguard.exception.UnsupportedManualCommandException;
import com.animalguard.repository.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
public class ManualDeviceCommandService {

    private static final String MANUAL_COMMAND_PREFIX = "manual-";
    private static final String USER_REQUEST_REASON = "USER_REQUEST";

    private final DeviceCommandRepository repository;
    private final DeviceControlProperties deviceControlProperties;
    private final ManualCommandPreflightService preflightService;
    private final OperatorTokenVerifier tokenVerifier;
    private final Clock clock;
    private final ReentrantLock manualCommandGate = new ReentrantLock(true);

    @Transactional
    public CommandDecision create(
            String deviceId,
            UUID requestId,
            DeviceCommandType commandType,
            String operatorToken
    ) {
        if (!preflightService.isOperatorApiEnabled()) {
            return CommandDecision.suppressed(List.of(ActuationBlocker.OPERATOR_API_DISABLED));
        }

        tokenVerifier.verify(operatorToken);
        requireSupportedCommand(commandType);
        if (!preflightService.isKnownDevice(deviceId)) {
            throw new UnknownDeviceException(deviceId);
        }

        manualCommandGate.lock();
        boolean releaseOnReturn = true;
        try {
            if (isTransactionSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        manualCommandGate.unlock();
                    }
                });
                releaseOnReturn = false;
            }

            String commandId = MANUAL_COMMAND_PREFIX + requestId;
            Optional<DeviceCommand> existing = repository.findByCommandId(commandId);
            if (existing.isPresent()) {
                return existingDecision(existing.orElseThrow(), deviceId, commandType);
            }

            List<ActuationBlocker> blockers = preflightService.blockersForCreation(commandType);
            if (!blockers.isEmpty()) {
                return CommandDecision.suppressed(blockers);
            }

            Instant issuedAt = clock.instant();
            DeviceCommand command = repository.save(new DeviceCommand(
                    commandId,
                    null,
                    deviceId,
                    DeviceCommandSource.MANUAL,
                    commandType,
                    null,
                    USER_REQUEST_REASON,
                    issuedAt,
                    issuedAt.plus(deviceControlProperties.commandTtl())
            ));
            return CommandDecision.created(command.getCommandId());
        } finally {
            if (releaseOnReturn) {
                manualCommandGate.unlock();
            }
        }
    }

    private CommandDecision existingDecision(
            DeviceCommand existing,
            String deviceId,
            DeviceCommandType commandType
    ) {
        if (existing.getSource() != DeviceCommandSource.MANUAL
                || !existing.getDeviceId().equals(deviceId)
                || existing.getCommandType() != commandType) {
            throw new ManualCommandConflictException();
        }
        return CommandDecision.created(existing.getCommandId());
    }

    private void requireSupportedCommand(DeviceCommandType commandType) {
        if (commandType != DeviceCommandType.ROTATE_CAMERA_LEFT
                && commandType != DeviceCommandType.ROTATE_CAMERA_RIGHT
                && commandType != DeviceCommandType.STOP_DETERRENT) {
            throw new UnsupportedManualCommandException();
        }
    }

    private boolean isTransactionSynchronizationActive() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive();
    }
}
