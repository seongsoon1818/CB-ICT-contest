package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.domain.ActuationBlocker;
import com.animalguard.domain.CommandOutcome;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCommandCreationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T03:00:00Z");

    @Mock
    private DeviceCommandRepository deviceCommandRepository;

    private DeviceControlProperties properties;
    private DeviceCommandCreationService service;

    @BeforeEach
    void setUp() {
        properties = properties(Map.of("cam-001", "pi-001"));
        service = service(properties, true, true, true);
    }

    @Test
    void suppressesCommandWhenActuationIsDisabledBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, false, true, true).createIfAllowed(
                event("event-disabled"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.ACTUATION_DISABLED);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenRiskPolicyIsUnconfirmedBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, true, false, true).createIfAllowed(
                event("event-policy"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.RISK_POLICY_UNCONFIRMED);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenCameraDeviceMappingIsEmptyBeforeCameraLookup() {
        DeviceControlProperties emptyMappings = properties(Map.of());

        CommandDecision decision = service(emptyMappings, true, true, true).createIfAllowed(
                event("event-empty-mappings"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_DEVICE_MAPPING_EMPTY);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenMqttPublisherIsNotReadyBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, true, true, false).createIfAllowed(
                event("event-transport"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void returnsEveryGlobalBlockerBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, false, false, false).createIfAllowed(
                event("event-global-blockers"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(
                ActuationBlocker.ACTUATION_DISABLED,
                ActuationBlocker.RISK_POLICY_UNCONFIRMED,
                ActuationBlocker.MQTT_PUBLISHER_NOT_READY
        );
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandForUnmappedCamera() {
        CommandDecision decision = service.createIfAllowed(
                event("event-unmapped"),
                "cam-unknown",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_UNMAPPED);

        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void createsCommandForMappedCameraWhenDeviceIsIdle() {
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.empty());
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDecision decision = service.createIfAllowed(
                event("event-idle"),
                "cam-001",
                DeviceCommandType.DETERRENT_FULL,
                "CLASS_SCORE_MAGPIE +30"
        );

        ArgumentCaptor<DeviceCommand> commandCaptor = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(deviceCommandRepository).save(commandCaptor.capture());
        DeviceCommand command = commandCaptor.getValue();

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(decision.commandId()).isEqualTo(command.getCommandId());
        assertThat(decision.blockers()).isEmpty();
        assertThat(command.getDeviceId()).isEqualTo("pi-001");
        assertThat(command.getCreatedAt()).isEqualTo(NOW);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(command.getSource()).isEqualTo(DeviceCommandSource.AUTOMATIC);
        assertThat(command.getCommandType()).isEqualTo(DeviceCommandType.DETERRENT_FULL);
        assertThat(command.getDurationMs()).isEqualTo(5_000);
        assertThat(command.getReason()).isEqualTo("CLASS_SCORE_MAGPIE +30");
        assertThat(command.getIssuedAt()).isEqualTo(NOW);
        assertThat(command.getExpiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void createsAutomaticStopWithoutDurationWhenCallerRequestsIt() {
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.empty());
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createIfAllowed(
                event("event-stop"),
                "cam-001",
                DeviceCommandType.STOP_DETERRENT,
                "ANIMAL_DISAPPEARED"
        );

        ArgumentCaptor<DeviceCommand> commandCaptor = ArgumentCaptor.forClass(DeviceCommand.class);
        verify(deviceCommandRepository).save(commandCaptor.capture());
        DeviceCommand command = commandCaptor.getValue();
        assertThat(command.getSource()).isEqualTo(DeviceCommandSource.AUTOMATIC);
        assertThat(command.getCommandType()).isEqualTo(DeviceCommandType.STOP_DETERRENT);
        assertThat(command.getDurationMs()).isNull();
    }

    @Test
    void suppressesCommandWhileLatestCommandIsWithinCooldown() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(19));
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.of(latest));

        CommandDecision decision = service.createIfAllowed(
                event("event-cooldown"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.COOLDOWN_ACTIVE);

        verify(deviceCommandRepository, never()).save(any(DeviceCommand.class));
    }

    @Test
    void createsCommandAtCooldownBoundary() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(20));
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.of(latest));
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDecision decision = service.createIfAllowed(
                event("event-boundary"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                "risk reason"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(decision.commandId()).isNotBlank();
        assertThat(decision.blockers()).isEmpty();
    }

    private DeviceCommand commandCreatedAt(Instant createdAt) {
        return new DeviceCommand(
                "command-existing",
                event("event-existing"),
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                5_000,
                "legacy test reason",
                createdAt,
                createdAt.plusSeconds(10)
        );
    }

    private DeviceCommandCreationService service(
            DeviceControlProperties deviceControlProperties,
            boolean enabled,
            boolean riskPolicyConfirmed,
            boolean transportReady
    ) {
        ActuationPreflightService preflightService = new ActuationPreflightService(
                new ActuationProperties(enabled, riskPolicyConfirmed),
                deviceControlProperties,
                () -> transportReady
        );
        return new DeviceCommandCreationService(
                deviceCommandRepository,
                deviceControlProperties,
                preflightService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private DeviceControlProperties properties(Map<String, String> mappings) {
        return new DeviceControlProperties(
                Duration.ofSeconds(20),
                Duration.ofSeconds(10),
                mappings
        );
    }

    private DetectionEvent event(String eventId) {
        return new DetectionEvent(
                eventId,
                "cam-001",
                NOW,
                1280,
                720,
                "animal-detector-v1",
                null
        );
    }
}
