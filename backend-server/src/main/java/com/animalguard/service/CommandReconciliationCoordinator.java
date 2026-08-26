package com.animalguard.service;

import com.animalguard.config.ReconciliationProperties;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CommandReconciliationCoordinator {

    private final DeviceCommandRepository repository;
    private final ReconciliationProperties properties;
    private final TransactionOperations transactionOperations;

    public boolean expireCreated(String commandId, Instant now) {
        return Boolean.TRUE.equals(transactionOperations.execute(status -> {
            Optional<DeviceCommand> found = repository.findByCommandId(commandId);
            if (found.isEmpty()) {
                return false;
            }
            DeviceCommand command = found.orElseThrow();
            if (command.getStatus() != DeviceCommandStatus.CREATED
                    || now.isBefore(command.getExpiresAt())) {
                return false;
            }
            command.markExpired(now, null);
            return true;
        }));
    }

    public boolean failPublishedTimeout(String commandId, Instant now) {
        return Boolean.TRUE.equals(transactionOperations.execute(status -> {
            Optional<DeviceCommand> found = repository.findByCommandId(commandId);
            if (found.isEmpty()) {
                return false;
            }
            DeviceCommand command = found.orElseThrow();
            if (command.getStatus() != DeviceCommandStatus.PUBLISHED
                    || now.isBefore(command.getPublishedAt().plus(properties.publishedTimeout()))) {
                return false;
            }
            command.markFailed(now, null);
            return true;
        }));
    }

    public boolean failAcknowledgedTimeout(String commandId, Instant now) {
        return Boolean.TRUE.equals(transactionOperations.execute(status -> {
            Optional<DeviceCommand> found = repository.findByCommandId(commandId);
            if (found.isEmpty()) {
                return false;
            }
            DeviceCommand command = found.orElseThrow();
            if (command.getStatus() != DeviceCommandStatus.ACKNOWLEDGED
                    || now.isBefore(command.getAcknowledgedAt().plus(properties.acknowledgedTimeout()))) {
                return false;
            }
            command.markFailed(now, null);
            return true;
        }));
    }
}
