package com.animalguard.mqtt;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.service.ActuationPreflightService;
import com.animalguard.service.ManualCommandPreflightService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandDispatchCoordinator {

    private final DeviceCommandRepository repository;
    private final ActuationPreflightService preflightService;
    private final ManualCommandPreflightService manualPreflightService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionOperations transactionOperations;

    public Optional<PreparedMqttCommand> prepare(String commandId) {
        Optional<PreparedMqttCommand> prepared = transactionOperations.execute(
                status -> prepareInTransaction(commandId)
        );
        return prepared == null ? Optional.empty() : prepared;
    }

    public boolean markPublishFailed(String commandId) {
        Boolean changed = transactionOperations.execute(status -> {
            Optional<DeviceCommand> found = repository.findByCommandId(commandId);
            if (found.isEmpty() || found.orElseThrow().getStatus() != DeviceCommandStatus.PUBLISHED) {
                return false;
            }
            found.orElseThrow().markFailed(clock.instant(), null);
            return true;
        });
        return Boolean.TRUE.equals(changed);
    }

    private Optional<PreparedMqttCommand> prepareInTransaction(String commandId) {
        Optional<DeviceCommand> found = repository.findByCommandId(commandId);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        DeviceCommand command = found.orElseThrow();
        if (command.getStatus() != DeviceCommandStatus.CREATED) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        List<ActuationBlocker> blockers = switch (command.getSource()) {
            case AUTOMATIC -> preflightService.blockersForAutomaticDispatch(
                    command.getCommandType(),
                    command.getDeviceId()
            );
            case MANUAL -> manualPreflightService.blockersForDispatch(
                    command.getCommandType(),
                    command.getDeviceId()
            );
        };
        if (!now.isBefore(command.getExpiresAt())) {
            command.markExpired(now, null);
            return Optional.empty();
        }
        if (!blockers.isEmpty()) {
            log.info(
                    "MQTT dispatch blocked by preflight: commandId={}, blockers={}",
                    command.getCommandId(),
                    blockers
            );
            return Optional.empty();
        }

        MqttCommandPayload payload = MqttCommandPayload.from(command);
        byte[] json = serialize(payload);
        command.markPublished(now);
        return Optional.of(new PreparedMqttCommand(
                command.getCommandId(),
                MqttTopicCodec.commandTopic(command.getDeviceId()),
                json
        ));
    }

    private byte[] serialize(MqttCommandPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException exception) {
            throw new MqttPayloadContractException("command payload serialization failed", exception);
        }
    }
}
