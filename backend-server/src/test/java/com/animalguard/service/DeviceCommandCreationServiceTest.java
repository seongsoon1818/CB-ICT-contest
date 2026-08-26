package com.animalguard.service;

import com.animalguard.config.ActuationProperties;
import com.animalguard.config.DeviceControlProperties;
import com.animalguard.config.ReconciliationProperties;
import com.animalguard.config.ResponsePolicyProperties;
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
        CommandDecision decision = service(properties, false, true, true).createAutomaticIfAllowed(
                event("event-disabled"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.ACTUATION_DISABLED);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenRiskPolicyIsUnconfirmedBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, true, false, true).createAutomaticIfAllowed(
                event("event-policy"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.RISK_POLICY_UNCONFIRMED);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenResponsePolicyIsDisabledBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, true, true, false, true).createAutomaticIfAllowed(
                event("event-response-policy"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.RESPONSE_POLICY_DISABLED);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenCameraDeviceMappingIsEmptyBeforeCameraLookup() {
        DeviceControlProperties emptyMappings = properties(Map.of());

        CommandDecision decision = service(emptyMappings, true, true, true).createAutomaticIfAllowed(
                event("event-empty-mappings"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_DEVICE_MAPPING_EMPTY);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesCommandWhenMqttPublisherIsNotReadyBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, true, true, false).createAutomaticIfAllowed(
                event("event-transport"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.blockers()).containsExactly(ActuationBlocker.MQTT_PUBLISHER_NOT_READY);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void returnsEveryGlobalBlockerBeforeRepositoryAccess() {
        CommandDecision decision = service(properties, false, false, false).createAutomaticIfAllowed(
                event("event-global-blockers"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
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
        CommandDecision decision = service.createAutomaticIfAllowed(
                event("event-unmapped"),
                "cam-unknown",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.commandId()).isNull();
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_UNMAPPED);

        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void suppressesSafetyStopForUnmappedCamera() {
        CommandDecision decision = service(properties, false, false, true).createAutomaticIfAllowed(
                event("event-unmapped-stop"),
                "cam-unknown",
                DeviceCommandType.STOP_DETERRENT,
                null,
                "ANIMAL_DISAPPEARED"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.CAMERA_UNMAPPED);
        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void createsCommandForMappedCameraWhenDeviceIsIdle() {
        when(deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                "pi-001",
                DeviceCommandSource.AUTOMATIC
        ))
                .thenReturn(Optional.empty());
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDecision decision = service.createAutomaticIfAllowed(
                event("event-idle"),
                "cam-001",
                DeviceCommandType.DETERRENT_FULL,
                7_000,
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
        assertThat(command.getDurationMs()).isEqualTo(7_000);
        assertThat(command.getReason()).isEqualTo("CLASS_SCORE_MAGPIE +30");
        assertThat(command.getIssuedAt()).isEqualTo(NOW);
        assertThat(command.getExpiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void createsAutomaticStopWithoutDurationWhenCallerRequestsIt() {
        when(deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                "pi-001",
                DeviceCommandSource.AUTOMATIC
        ))
                .thenReturn(Optional.empty());
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createAutomaticIfAllowed(
                event("event-stop"),
                "cam-001",
                DeviceCommandType.STOP_DETERRENT,
                null,
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
    void stopBypassesDisabledActuationRiskPolicyAndCooldown() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(1));
        when(deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                "pi-001",
                DeviceCommandSource.AUTOMATIC
        )).thenReturn(Optional.of(latest));
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDecision decision = service(properties, false, false, true).createAutomaticIfAllowed(
                event("event-stop-safety"),
                "cam-001",
                DeviceCommandType.STOP_DETERRENT,
                null,
                "ANIMAL_DISAPPEARED"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
    }

    @Test
    void allowsSemanticTransitionDuringCooldown() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(1));
        when(deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                "pi-001",
                DeviceCommandSource.AUTOMATIC
        )).thenReturn(Optional.of(latest));
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDecision decision = service.createAutomaticIfAllowed(
                event("event-semantic-transition"),
                "cam-001",
                DeviceCommandType.DETERRENT_FULL,
                5_000,
                "PERSISTENT_ANIMAL_DETECTION"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
    }

    @Test
    void suppressesCommandWhileLatestCommandIsWithinCooldown() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(19));
        when(deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                "pi-001",
                DeviceCommandSource.AUTOMATIC
        ))
                .thenReturn(Optional.of(latest));

        CommandDecision decision = service.createAutomaticIfAllowed(
                event("event-cooldown"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
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
        when(deviceCommandRepository.findTopByDeviceIdAndSourceOrderByCreatedAtDesc(
                "pi-001",
                DeviceCommandSource.AUTOMATIC
        ))
                .thenReturn(Optional.of(latest));
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CommandDecision decision = service.createAutomaticIfAllowed(
                event("event-boundary"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "risk reason"
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.CREATED);
        assertThat(decision.commandId()).isNotBlank();
        assertThat(decision.blockers()).isEmpty();
    }

    @Test
    void suppressesAutomaticCommandWhenSessionAttemptLimitIsReached() {
        Instant sessionStartedAt = NOW.minusSeconds(30);
        when(deviceCommandRepository.countAutomaticAttemptsInObservationSession(
                "cam-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                sessionStartedAt
        )).thenReturn(3L);

        CommandDecision decision = service.createAutomaticForSessionIfAllowed(
                event("event-attempt-exhausted"),
                "cam-001",
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "FIRST_ANIMAL_DETECTION",
                sessionStartedAt
        );

        assertThat(decision.outcome()).isEqualTo(CommandOutcome.SUPPRESSED);
        assertThat(decision.blockers()).containsExactly(ActuationBlocker.AUTOMATIC_RETRY_EXHAUSTED);
        verify(deviceCommandRepository, never()).save(any(DeviceCommand.class));
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
        return service(
                deviceControlProperties,
                enabled,
                riskPolicyConfirmed,
                true,
                transportReady
        );
    }

    private DeviceCommandCreationService service(
            DeviceControlProperties deviceControlProperties,
            boolean enabled,
            boolean riskPolicyConfirmed,
            boolean responsePolicyEnabled,
            boolean transportReady
    ) {
        ActuationPreflightService preflightService = new ActuationPreflightService(
                new ActuationProperties(enabled, riskPolicyConfirmed),
                new ResponsePolicyProperties(
                        responsePolicyEnabled,
                        responsePolicyEnabled ? java.util.Set.of("MAGPIE") : java.util.Set.of(),
                        0.0,
                        null,
                        null
                ),
                deviceControlProperties,
                () -> transportReady
        );
        return new DeviceCommandCreationService(
                deviceCommandRepository,
                deviceControlProperties,
                preflightService,
                new ReconciliationProperties(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(15),
                        Duration.ofSeconds(15),
                        3
                ),
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
