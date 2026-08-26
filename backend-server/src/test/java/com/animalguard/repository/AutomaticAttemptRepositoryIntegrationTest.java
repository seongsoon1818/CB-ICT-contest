package com.animalguard.repository;

import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class AutomaticAttemptRepositoryIntegrationTest {

    private static final Instant SESSION_START = Instant.parse("2026-08-26T00:00:10Z");

    @Autowired
    private DeviceCommandRepository commandRepository;

    @Autowired
    private DetectionEventRepository eventRepository;

    @Test
    void countsOnlySameCameraSourceTypeAndDetectionTimeSession() {
        saveAutomatic("before-session", "cam-001", SESSION_START.minusSeconds(1), DeviceCommandType.SOUND_ALERT);
        saveAutomatic("at-session", "cam-001", SESSION_START, DeviceCommandType.SOUND_ALERT);
        DeviceCommand terminal = saveAutomatic(
                "terminal-in-session",
                "cam-001",
                SESSION_START.plusSeconds(1),
                DeviceCommandType.SOUND_ALERT
        );
        terminal.markExpired(SESSION_START.plusSeconds(2), null);
        saveAutomatic("other-camera", "cam-002", SESSION_START.plusSeconds(1), DeviceCommandType.SOUND_ALERT);
        saveAutomatic("other-type", "cam-001", SESSION_START.plusSeconds(1), DeviceCommandType.DETERRENT_FULL);
        commandRepository.flush();

        assertThat(commandRepository.countAutomaticAttemptsInObservationSession(
                "cam-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                SESSION_START
        )).isEqualTo(2L);
    }

    private DeviceCommand saveAutomatic(
            String commandId,
            String cameraId,
            Instant capturedAt,
            DeviceCommandType type
    ) {
        DetectionEvent event = eventRepository.save(new DetectionEvent(
                UUID.randomUUID().toString(),
                cameraId,
                capturedAt,
                1280,
                720,
                "animal-detector-v1",
                null
        ));
        Integer durationMs = type == DeviceCommandType.STOP_DETERRENT ? null : 2_000;
        return commandRepository.save(new DeviceCommand(
                commandId,
                event,
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                type,
                durationMs,
                "ATTEMPT_TEST",
                capturedAt,
                capturedAt.plusSeconds(10)
        ));
    }
}
