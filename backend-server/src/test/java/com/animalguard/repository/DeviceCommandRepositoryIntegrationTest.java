package com.animalguard.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class DeviceCommandRepositoryIntegrationTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-08-25T03:00:00Z");

    @Autowired
    private DeviceCommandRepository deviceCommandRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void preservesLegacyAuditRowWithoutMaterializingItAsCurrentEnumEntity() {
        jdbcTemplate.update(
                """
                INSERT INTO detection_events (
                    event_id, camera_id, captured_at, image_width, image_height,
                    detector_version, classifier_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-event-repository-test",
                "cam-legacy",
                CREATED_AT,
                1280,
                720,
                "legacy-detector",
                null,
                CREATED_AT
        );
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM detection_events WHERE event_id = ?",
                Long.class,
                "legacy-event-repository-test"
        );
        jdbcTemplate.update(
                """
                INSERT INTO device_commands (
                    command_id, event_id, device_id, command_source, command_type,
                    duration_ms, reason, issued_at, expires_at, status, created_at,
                    expired_at, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-command-repository-test",
                eventId,
                "pi-legacy",
                "AUTOMATIC",
                "DETERRENT_LEVEL_2",
                5_000,
                "LEGACY_PRE_MQTT_COMMAND",
                CREATED_AT,
                CREATED_AT,
                "EXPIRED",
                CREATED_AT,
                CREATED_AT,
                0
        );

        assertThat(jdbcTemplate.queryForObject(
                "SELECT command_type FROM device_commands WHERE command_id = ?",
                String.class,
                "legacy-command-repository-test"
        )).isEqualTo("DETERRENT_LEVEL_2");
        assertThat(deviceCommandRepository.findTopByDeviceIdOrderByCreatedAtDesc("pi-legacy"))
                .isEmpty();
    }
}
