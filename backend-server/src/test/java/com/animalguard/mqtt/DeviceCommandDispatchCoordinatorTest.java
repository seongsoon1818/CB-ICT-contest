package com.animalguard.mqtt;

import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import com.animalguard.service.ActuationPreflightService;
import com.animalguard.service.ManualCommandPreflightService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviceCommandDispatchCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-08-26T00:00:05Z");
    private static final String EVENT_ID = "15356786-9588-4db4-a0fe-f8acd6300868";

    private final DeviceCommandRepository repository = mock(DeviceCommandRepository.class);
    private final ActuationPreflightService preflightService = mock(ActuationPreflightService.class);
    private final ManualCommandPreflightService manualPreflightService = mock(ManualCommandPreflightService.class);
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final DeviceCommandDispatchCoordinator coordinator = new DeviceCommandDispatchCoordinator(
            repository,
            preflightService,
            manualPreflightService,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new DirectTransactionOperations()
    );

    @Test
    void preparesPayloadAndMarksPublishedInsideTransaction() {
        DeviceCommand command = automaticCommand("command-ready", NOW.plusSeconds(5));
        when(repository.findByCommandId("command-ready")).thenReturn(Optional.of(command));
        when(preflightService.blockersForAutomaticDispatch(DeviceCommandType.SOUND_ALERT, "pi-001"))
                .thenReturn(List.of());

        Optional<PreparedMqttCommand> result = coordinator.prepare("command-ready");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().commandId()).isEqualTo("command-ready");
        assertThat(result.orElseThrow().topic())
                .isEqualTo("animalguard/devices/pi-001/commands");
        assertThat(new String(result.orElseThrow().payload(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"commandId\":\"command-ready\"")
                .contains("\"eventId\":\"" + EVENT_ID + "\"")
                .contains("\"durationMs\":2000");
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
        assertThat(command.getPublishedAt()).isEqualTo(NOW);
        verifyNoInteractions(manualPreflightService);
    }

    @Test
    void preparesManualRotationWithNullEventAndDuration() {
        DeviceCommand command = manualCommand(
                "manual-15356786-9588-4db4-a0fe-f8acd6300868",
                DeviceCommandType.ROTATE_CAMERA_LEFT,
                NOW.plusSeconds(5)
        );
        when(repository.findByCommandId(command.getCommandId())).thenReturn(Optional.of(command));
        when(manualPreflightService.blockersForDispatch(
                DeviceCommandType.ROTATE_CAMERA_LEFT,
                "pi-001"
        )).thenReturn(List.of());

        Optional<PreparedMqttCommand> result = coordinator.prepare(command.getCommandId());

        assertThat(result).isPresent();
        assertThat(new String(result.orElseThrow().payload(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("\"eventId\":null")
                .contains("\"source\":\"MANUAL\"")
                .contains("\"command\":\"ROTATE_CAMERA_LEFT\"")
                .contains("\"durationMs\":null")
                .contains("\"reason\":\"USER_REQUEST\"");
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.PUBLISHED);
        verifyNoInteractions(preflightService);
    }

    @Test
    void leavesManualCommandCreatedWhenSourceSpecificRecheckIsBlocked() {
        DeviceCommand command = manualCommand(
                "manual-15356786-9588-4db4-a0fe-f8acd6300869",
                DeviceCommandType.STOP_DETERRENT,
                NOW.plusSeconds(5)
        );
        when(repository.findByCommandId(command.getCommandId())).thenReturn(Optional.of(command));
        when(manualPreflightService.blockersForDispatch(
                DeviceCommandType.STOP_DETERRENT,
                "pi-001"
        )).thenReturn(List.of(ActuationBlocker.OPERATOR_API_DISABLED));

        assertThat(coordinator.prepare(command.getCommandId())).isEmpty();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        verifyNoInteractions(preflightService);
    }

    @Test
    void expiresCreatedCommandAtBoundaryWithoutPreparingPublish() {
        DeviceCommand command = automaticCommand("command-expired", NOW);
        when(repository.findByCommandId("command-expired")).thenReturn(Optional.of(command));
        when(preflightService.blockersForAutomaticDispatch(DeviceCommandType.SOUND_ALERT, "pi-001"))
                .thenReturn(List.of());

        assertThat(coordinator.prepare("command-expired")).isEmpty();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.EXPIRED);
        assertThat(command.getExpiredAt()).isEqualTo(NOW);
        assertThat(command.getExpiredReportedAt()).isNull();
    }

    @Test
    void leavesBlockedPreflightCommandCreatedForLaterReadiness() {
        DeviceCommand command = automaticCommand("command-blocked", NOW.plusSeconds(5));
        when(repository.findByCommandId("command-blocked")).thenReturn(Optional.of(command));
        when(preflightService.blockersForAutomaticDispatch(DeviceCommandType.SOUND_ALERT, "pi-001"))
                .thenReturn(List.of(ActuationBlocker.MQTT_PUBLISHER_NOT_READY));

        assertThat(coordinator.prepare("command-blocked")).isEmpty();

        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
    }

    @Test
    void rechecksCreatedAndAutomaticStateBeforePreflight() {
        DeviceCommand published = automaticCommand("command-published", NOW.plusSeconds(5));
        published.markPublished(NOW.minusSeconds(1));
        when(repository.findByCommandId("command-published")).thenReturn(Optional.of(published));

        assertThat(coordinator.prepare("command-published")).isEmpty();

        verifyNoInteractions(preflightService, manualPreflightService);
    }

    @Test
    void rejectsInvalidPayloadWithoutAdvancingCreatedState() {
        DeviceCommand command = automaticCommand("command-invalid", NOW.plusSeconds(5), "not-a-uuid");
        when(repository.findByCommandId("command-invalid")).thenReturn(Optional.of(command));
        when(preflightService.blockersForAutomaticDispatch(DeviceCommandType.SOUND_ALERT, "pi-001"))
                .thenReturn(List.of());

        assertThatThrownBy(() -> coordinator.prepare("command-invalid"))
                .isInstanceOf(MqttPayloadContractException.class);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
    }

    @Test
    void marksOnlyPublishedCommandFailedWithBackendTimestamp() {
        DeviceCommand published = automaticCommand("command-failed", NOW.plusSeconds(5));
        published.markPublished(NOW.minusSeconds(1));
        when(repository.findByCommandId("command-failed")).thenReturn(Optional.of(published));

        assertThat(coordinator.markPublishFailed("command-failed")).isTrue();

        assertThat(published.getStatus()).isEqualTo(DeviceCommandStatus.FAILED);
        assertThat(published.getFailedAt()).isEqualTo(NOW);
        assertThat(published.getFailedReportedAt()).isNull();
    }

    @Test
    void doesNotTurnCreatedCommandIntoFailed() {
        DeviceCommand created = automaticCommand("command-created", NOW.plusSeconds(5));
        when(repository.findByCommandId("command-created")).thenReturn(Optional.of(created));

        assertThat(coordinator.markPublishFailed("command-created")).isFalse();

        assertThat(created.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
    }

    private DeviceCommand automaticCommand(String commandId, Instant expiresAt) {
        return automaticCommand(commandId, expiresAt, EVENT_ID);
    }

    private DeviceCommand automaticCommand(String commandId, Instant expiresAt, String eventId) {
        Instant issuedAt = NOW.minusSeconds(5);
        return new DeviceCommand(
                commandId,
                new DetectionEvent(
                        eventId,
                        "cam-001",
                        issuedAt,
                        1280,
                        720,
                        "animal-detector-v1",
                        null
                ),
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "FIRST_ANIMAL_DETECTION",
                issuedAt,
                expiresAt
        );
    }

    private DeviceCommand manualCommand(
            String commandId,
            DeviceCommandType commandType,
            Instant expiresAt
    ) {
        Instant issuedAt = NOW.minusSeconds(5);
        return new DeviceCommand(
                commandId,
                null,
                "pi-001",
                DeviceCommandSource.MANUAL,
                commandType,
                null,
                "USER_REQUEST",
                issuedAt,
                expiresAt
        );
    }

    private static final class DirectTransactionOperations implements TransactionOperations {

        private final TransactionStatus status = mock(TransactionStatus.class);

        @Override
        public <T> T execute(TransactionCallback<T> action) throws TransactionException {
            return action.doInTransaction(status);
        }
    }
}
