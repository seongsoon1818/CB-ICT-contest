package com.animalguard.repository;

import com.animalguard.domain.DetectionEvent;
import com.animalguard.domain.DeviceCommand;
import com.animalguard.domain.DeviceCommandSource;
import com.animalguard.domain.DeviceCommandStatus;
import com.animalguard.domain.DeviceCommandType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class DeviceCommandDispatchRepositoryIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-08-26T00:00:00Z");

    @Autowired
    private DeviceCommandRepository commandRepository;

    @Autowired
    private DetectionEventRepository eventRepository;

    @Test
    void returnsOldestCreatedCommandsAcrossAutomaticAndManualSourcesWithinBatchSize() {
        saveAutomatic("command-third", 3);
        saveAutomatic("command-first", 1);
        saveAutomatic("command-second", 2);
        saveManual("command-manual", 0);
        DeviceCommand published = saveAutomatic("command-published", 0);
        published.markPublished(BASE.plusSeconds(1));
        commandRepository.flush();

        assertThat(commandRepository.findDispatchCandidateCommandIds(
                DeviceCommandStatus.CREATED,
                PageRequest.of(0, 2)
        )).containsExactly("command-manual", "command-first");
    }

    private DeviceCommand saveAutomatic(String commandId, long seconds) {
        Instant issuedAt = BASE.plusSeconds(seconds);
        DetectionEvent event = eventRepository.save(new DetectionEvent(
                java.util.UUID.randomUUID().toString(),
                "cam-001",
                issuedAt,
                1280,
                720,
                "animal-detector-v1",
                null
        ));
        return commandRepository.save(new DeviceCommand(
                commandId,
                event,
                "pi-001",
                DeviceCommandSource.AUTOMATIC,
                DeviceCommandType.SOUND_ALERT,
                2_000,
                "FIRST_ANIMAL_DETECTION",
                issuedAt,
                issuedAt.plusSeconds(10)
        ));
    }

    private void saveManual(String commandId, long seconds) {
        Instant issuedAt = BASE.plusSeconds(seconds);
        commandRepository.save(new DeviceCommand(
                commandId,
                null,
                "pi-001",
                DeviceCommandSource.MANUAL,
                DeviceCommandType.ROTATE_CAMERA_LEFT,
                null,
                "USER_REQUEST",
                issuedAt,
                issuedAt.plusSeconds(10)
        ));
    }
}
