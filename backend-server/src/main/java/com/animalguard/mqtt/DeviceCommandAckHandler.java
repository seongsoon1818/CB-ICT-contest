package com.animalguard.mqtt;

import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DeviceCommandAckHandler {

    private final DeviceCommandRepository repository;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public AckHandlingResult handle(MqttAckMessage message, String topicDeviceId) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(topicDeviceId, "topicDeviceId must not be null");
        if (!topicDeviceId.equals(message.deviceId())) {
            return result(AckHandlingOutcome.TOPIC_PAYLOAD_DEVICE_MISMATCH, null);
        }

        Instant receivedAt = clock.instant();
        try {
            return executeOnce(message, receivedAt);
        } catch (ObjectOptimisticLockingFailureException firstConflict) {
            try {
                return executeOnce(message, receivedAt).retried();
            } catch (ObjectOptimisticLockingFailureException secondConflict) {
                return new AckHandlingResult(
                        AckHandlingOutcome.OPTIMISTIC_LOCK_ABORTED,
                        null,
                        true
                );
            }
        }
    }

    private AckHandlingResult executeOnce(MqttAckMessage message, Instant receivedAt) {
        AckHandlingResult result = transactionOperations.execute(
                status -> applyInTransaction(message, receivedAt)
        );
        if (result == null) {
            throw new IllegalStateException("ACK transaction returned no result");
        }
        return result;
    }

    private AckHandlingResult applyInTransaction(MqttAckMessage message, Instant receivedAt) {
        Optional<DeviceCommand> found = repository.findByCommandId(message.commandId());
        if (found.isEmpty()) {
            return result(AckHandlingOutcome.UNKNOWN_COMMAND, null);
        }

        DeviceCommand command = found.orElseThrow();
        DeviceCommandStatus current = command.getStatus();
        if (!command.getDeviceId().equals(message.deviceId())) {
            return result(AckHandlingOutcome.DATABASE_DEVICE_MISMATCH, current);
        }

        return switch (message.status()) {
            case ACKNOWLEDGED -> acknowledge(command, current, message.reportedAt(), receivedAt);
            case EXECUTED -> execute(command, current, message.reportedAt(), receivedAt);
            case FAILED -> fail(command, current, message.reportedAt(), receivedAt);
            case EXPIRED -> expire(command, current, message.reportedAt(), receivedAt);
        };
    }

    private AckHandlingResult acknowledge(
            DeviceCommand command,
            DeviceCommandStatus current,
            Instant reportedAt,
            Instant receivedAt
    ) {
        return switch (current) {
            case PUBLISHED -> {
                command.markAcknowledged(receivedAt, reportedAt);
                yield result(AckHandlingOutcome.APPLIED, DeviceCommandStatus.ACKNOWLEDGED);
            }
            case ACKNOWLEDGED -> result(AckHandlingOutcome.IDEMPOTENT, current);
            case EXECUTED -> result(AckHandlingOutcome.ADVANCED_IGNORED, current);
            case CREATED -> result(AckHandlingOutcome.ORDER_VIOLATION, current);
            case FAILED, EXPIRED -> result(AckHandlingOutcome.TERMINAL_CONFLICT, current);
        };
    }

    private AckHandlingResult execute(
            DeviceCommand command,
            DeviceCommandStatus current,
            Instant reportedAt,
            Instant receivedAt
    ) {
        return switch (current) {
            case ACKNOWLEDGED -> {
                command.markExecuted(receivedAt, reportedAt);
                yield result(AckHandlingOutcome.APPLIED, DeviceCommandStatus.EXECUTED);
            }
            case EXECUTED -> result(AckHandlingOutcome.IDEMPOTENT, current);
            case CREATED, PUBLISHED -> result(AckHandlingOutcome.ORDER_VIOLATION, current);
            case FAILED, EXPIRED -> result(AckHandlingOutcome.TERMINAL_CONFLICT, current);
        };
    }

    private AckHandlingResult fail(
            DeviceCommand command,
            DeviceCommandStatus current,
            Instant reportedAt,
            Instant receivedAt
    ) {
        return switch (current) {
            case PUBLISHED, ACKNOWLEDGED -> {
                command.markFailed(receivedAt, reportedAt);
                yield result(AckHandlingOutcome.APPLIED, DeviceCommandStatus.FAILED);
            }
            case FAILED -> result(AckHandlingOutcome.IDEMPOTENT, current);
            case CREATED -> result(AckHandlingOutcome.ORDER_VIOLATION, current);
            case EXECUTED, EXPIRED -> result(AckHandlingOutcome.TERMINAL_CONFLICT, current);
        };
    }

    private AckHandlingResult expire(
            DeviceCommand command,
            DeviceCommandStatus current,
            Instant reportedAt,
            Instant receivedAt
    ) {
        return switch (current) {
            case CREATED, PUBLISHED -> {
                command.markExpired(receivedAt, reportedAt);
                yield result(AckHandlingOutcome.APPLIED, DeviceCommandStatus.EXPIRED);
            }
            case EXPIRED -> result(AckHandlingOutcome.IDEMPOTENT, current);
            case ACKNOWLEDGED, EXECUTED, FAILED -> result(AckHandlingOutcome.TERMINAL_CONFLICT, current);
        };
    }

    private AckHandlingResult result(AckHandlingOutcome outcome, DeviceCommandStatus currentStatus) {
        return new AckHandlingResult(outcome, currentStatus, false);
    }
}
