package com.animalguard.mqtt;

import com.animalguard.config.MqttProperties;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceCommandDispatcherTest {

    private final DeviceCommandRepository repository = mock(DeviceCommandRepository.class);
    private final DeviceCommandDispatchCoordinator coordinator = mock(DeviceCommandDispatchCoordinator.class);
    private final MqttCommandTransport transport = mock(MqttCommandTransport.class);

    @Test
    void disabledMqttDoesNotQueryOrPublish() {
        dispatcher(false).dispatch();

        verifyNoInteractions(repository, coordinator, transport);
    }

    @Test
    void queriesOldestAutomaticCreatedBatchAndPublishesAfterPreparationReturns() {
        byte[] payload = "{\"commandId\":\"command-001\"}".getBytes(StandardCharsets.UTF_8);
        when(repository.findDispatchCandidateCommandIds(
                DeviceCommandStatus.CREATED,
                DeviceCommandSource.AUTOMATIC,
                PageRequest.of(0, 20)
        )).thenReturn(List.of("command-001"));
        when(coordinator.prepare("command-001")).thenReturn(Optional.of(new PreparedMqttCommand(
                "command-001",
                "animalguard/devices/pi-001/commands",
                payload
        )));

        dispatcher(true).dispatch();

        org.mockito.InOrder order = inOrder(coordinator, transport);
        order.verify(coordinator).prepare("command-001");
        order.verify(transport).publish(
                "animalguard/devices/pi-001/commands",
                payload,
                1,
                false
        );
        verify(transport, times(1)).publish(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void blockedOrExpiredPreparationDoesNotPublish() {
        candidates("command-blocked");
        when(coordinator.prepare("command-blocked")).thenReturn(Optional.empty());

        dispatcher(true).dispatch();

        verifyNoInteractions(transport);
    }

    @Test
    void immediatePublishFailureMarksCurrentPublishedCommandFailedWithoutRetry() {
        PreparedMqttCommand prepared = prepared("command-failed");
        candidates("command-failed");
        when(coordinator.prepare("command-failed")).thenReturn(Optional.of(prepared));
        org.mockito.Mockito.doThrow(new MqttTransportException("broker unavailable"))
                .when(transport).publish(any(), any(), anyInt(), anyBoolean());
        when(coordinator.markPublishFailed("command-failed")).thenReturn(true);

        dispatcher(true).dispatch();

        verify(transport, times(1)).publish(any(), any(), anyInt(), anyBoolean());
        verify(coordinator).markPublishFailed("command-failed");
        verify(coordinator, times(1)).prepare("command-failed");
    }

    @Test
    void optimisticLockConflictDoesNotRetryOrPublish() {
        candidates("command-conflict");
        when(coordinator.prepare("command-conflict"))
                .thenThrow(new ObjectOptimisticLockingFailureException("DeviceCommand", "command-conflict"));

        dispatcher(true).dispatch();

        verify(coordinator, times(1)).prepare("command-conflict");
        verifyNoInteractions(transport);
    }

    @Test
    void payloadContractErrorDoesNotBlockNextCandidate() {
        when(repository.findDispatchCandidateCommandIds(
                DeviceCommandStatus.CREATED,
                DeviceCommandSource.AUTOMATIC,
                PageRequest.of(0, 20)
        )).thenReturn(List.of("command-invalid", "command-valid"));
        when(coordinator.prepare("command-invalid"))
                .thenThrow(new MqttPayloadContractException("invalid payload"));
        when(coordinator.prepare("command-valid")).thenReturn(Optional.of(prepared("command-valid")));

        dispatcher(true).dispatch();

        verify(transport).publish(
                org.mockito.ArgumentMatchers.eq("animalguard/devices/pi-001/commands"),
                any(byte[].class),
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(false)
        );
    }

    private void candidates(String commandId) {
        when(repository.findDispatchCandidateCommandIds(
                DeviceCommandStatus.CREATED,
                DeviceCommandSource.AUTOMATIC,
                PageRequest.of(0, 20)
        )).thenReturn(List.of(commandId));
    }

    private PreparedMqttCommand prepared(String commandId) {
        return new PreparedMqttCommand(
                commandId,
                "animalguard/devices/pi-001/commands",
                ("{\"commandId\":\"" + commandId + "\"}").getBytes(StandardCharsets.UTF_8)
        );
    }

    private DeviceCommandDispatcher dispatcher(boolean enabled) {
        return new DeviceCommandDispatcher(properties(enabled), repository, coordinator, transport);
    }

    private MqttProperties properties(boolean enabled) {
        return new MqttProperties(
                enabled,
                "127.0.0.1",
                1883,
                "animalguard-backend",
                "",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMillis(500),
                20
        );
    }
}
