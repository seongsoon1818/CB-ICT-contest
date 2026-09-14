package com.animalguard.repository;

import com.animalguard.domain.AnimalDetection;
import com.animalguard.domain.DetectionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
class AnimalDetectionRepositoryIntegrationTest {

    @Autowired
    private DetectionEventRepository detectionEventRepository;

    @Test
    void rejectsDuplicateDetectionIdWithinSameEventAtDatabaseBoundary() {
        DetectionEvent event = new DetectionEvent(
                "15356786-9588-4db4-a0fe-f8acd6300868",
                "cam-001",
                Instant.parse("2026-08-24T08:00:00Z"),
                1280,
                720,
                "animal-detector-v1",
                null
        );
        event.addDetection(detection("det-001"));
        event.addDetection(detection("det-001"));

        assertThatThrownBy(() -> detectionEventRepository.saveAndFlush(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private AnimalDetection detection(String detectionId) {
        return new AnimalDetection(
                detectionId,
                null,
                "UNKNOWN",
                0.50,
                0.50,
                10,
                20,
                30,
                40
        );
    }
}
