package com.animalguard.service;

import com.animalguard.config.DeviceControlProperties;
import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.repository.DeviceCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private DeviceCommandCreationService service;

    @BeforeEach
    void setUp() {
        service = new DeviceCommandCreationService(
                deviceCommandRepository,
                new DeviceControlProperties(
                        Duration.ofSeconds(20),
                        Duration.ofSeconds(10),
                        Map.of("cam-001", "pi-001")
                ),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void suppressesCommandForUnmappedCamera() {
        assertThat(service.createIfAllowed(event("event-unmapped"), "cam-unknown", "risk reason")).isEmpty();

        verifyNoInteractions(deviceCommandRepository);
    }

    @Test
    void createsCommandForMappedCameraWhenDeviceIsIdle() {
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.empty());
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeviceCommand command = service.createIfAllowed(
                event("event-idle"),
                "cam-001",
                "CLASS_SCORE_MAGPIE +30"
        ).orElseThrow();

        assertThat(command.getDeviceId()).isEqualTo("pi-001");
        assertThat(command.getCreatedAt()).isEqualTo(NOW);
        assertThat(command.getStatus()).isEqualTo(DeviceCommandStatus.CREATED);
        assertThat(command.getCommandType()).isEqualTo("DETERRENT_LEVEL_2");
        assertThat(command.getDurationMs()).isEqualTo(5_000);
        assertThat(command.getReason()).isEqualTo("CLASS_SCORE_MAGPIE +30");
        assertThat(command.getIssuedAt()).isEqualTo(NOW);
        assertThat(command.getExpiresAt()).isEqualTo(NOW.plusSeconds(10));
    }

    @Test
    void suppressesCommandWhileLatestCommandIsWithinCooldown() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(19));
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.of(latest));

        assertThat(service.createIfAllowed(event("event-cooldown"), "cam-001", "risk reason")).isEmpty();

        verify(deviceCommandRepository, never()).save(any(DeviceCommand.class));
    }

    @Test
    void createsCommandAtCooldownBoundary() {
        DeviceCommand latest = commandCreatedAt(NOW.minusSeconds(20));
        when(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-001"))
                .thenReturn(Optional.of(latest));
        when(deviceCommandRepository.save(any(DeviceCommand.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(service.createIfAllowed(event("event-boundary"), "cam-001", "risk reason")).isPresent();
    }

    private DeviceCommand commandCreatedAt(Instant createdAt) {
        return new DeviceCommand(
                "command-existing",
                event("event-existing"),
                "pi-001",
                "DETERRENT_LEVEL_2",
                5_000,
                "legacy test reason",
                createdAt,
                createdAt.plusSeconds(10)
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
