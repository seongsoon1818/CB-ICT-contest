package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.DeviceCommandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandDispatcher {

    private static final int COMMAND_QOS = 1;
    private static final boolean COMMAND_RETAINED = false;

    private final MqttProperties properties;
    private final DeviceCommandRepository repository;
    private final DeviceCommandDispatchCoordinator coordinator;
    private final MqttCommandTransport transport;

    @Scheduled(fixedDelayString = "${animalguard.mqtt.dispatch-interval:500ms}")
    public void dispatch() {
        if (!properties.enabled()) {
            return;
        }

        List<String> commandIds = repository.findDispatchCandidateCommandIds(
                DeviceCommandStatus.CREATED,
                PageRequest.of(0, properties.dispatchBatchSize())
        );
        commandIds.forEach(this::dispatchOne);
    }

    private void dispatchOne(String commandId) {
        Optional<PreparedMqttCommand> prepared;
        try {
            prepared = coordinator.prepare(commandId);
        } catch (MqttPayloadContractException exception) {
            log.error(
                    "MQTT command payload contract rejected before publish: commandId={}, reason={}",
                    commandId,
                    exception.getMessage()
            );
            return;
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.warn(
                    "MQTT command preparation lost optimistic-lock race without publish retry: commandId={}",
                    commandId
            );
            return;
        }

        if (prepared.isEmpty()) {
            return;
        }

        PreparedMqttCommand command = prepared.orElseThrow();
        try {
            transport.publish(
                    command.topic(),
                    command.payload(),
                    COMMAND_QOS,
                    COMMAND_RETAINED
            );
        } catch (MqttTransportException exception) {
            log.warn(
                    "MQTT command publish failed without retry: commandId={}, reason={}",
                    command.commandId(),
                    exception.getMessage()
            );
            recordImmediatePublishFailure(command.commandId());
        }
    }

    private void recordImmediatePublishFailure(String commandId) {
        try {
            if (!coordinator.markPublishFailed(commandId)) {
                log.warn(
                        "MQTT publish failure did not change non-PUBLISHED command: commandId={}",
                        commandId
                );
            }
        } catch (ObjectOptimisticLockingFailureException exception) {
            log.error(
                    "MQTT publish failure status update lost optimistic-lock race without retry: commandId={}",
                    commandId
            );
        }
    }
}
