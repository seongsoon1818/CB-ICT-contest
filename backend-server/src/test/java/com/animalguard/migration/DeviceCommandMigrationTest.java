package com.animalguard.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCommandMigrationTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-08-25T03:00:00Z");

    @Test
    void expiresExistingV1CommandBeforeAddingRequiredDeliveryFields() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("1")
                .cleanDisabled(true)
                .load()
                .migrate();

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.update(
                """
                INSERT INTO detection_events (
                    event_id, camera_id, captured_at, image_width, image_height,
                    detector_version, classifier_version, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-event-001",
                "cam-001",
                CREATED_AT,
                1280,
                720,
                "animal-detector-v1",
                null,
                CREATED_AT
        );
        Long eventId = jdbcTemplate.queryForObject(
                "SELECT id FROM detection_events WHERE event_id = ?",
                Long.class,
                "legacy-event-001"
        );
        jdbcTemplate.update(
                """
                INSERT INTO device_commands (
                    command_id, event_id, device_id, command_type, duration_ms, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "legacy-command-001",
                eventId,
                "pi-001",
                "DETERRENT_LEVEL_2",
                5_000,
                "CREATED",
                CREATED_AT
        );

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_commands WHERE command_id = 'legacy-command-001'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM device_commands WHERE command_id = 'legacy-command-001'",
                String.class
        )).isEqualTo("EXPIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM device_commands WHERE command_id = 'legacy-command-001'",
                String.class
        )).isEqualTo("LEGACY_PRE_MQTT_COMMAND");
        assertTimestampEqualsCreatedAt(jdbcTemplate, "issued_at");
        assertTimestampEqualsCreatedAt(jdbcTemplate, "expires_at");
        assertTimestampEqualsCreatedAt(jdbcTemplate, "expired_at");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM device_commands WHERE command_id = 'legacy-command-001'",
                Long.class
        )).isZero();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT acknowledged_reported_at, executed_reported_at, failed_reported_at, "
                        + "expired_reported_at FROM device_commands WHERE command_id = 'legacy-command-001'"
        ).values()).containsOnlyNulls();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" "
                        + "WHERE \"version\" IN ('1', '2') AND \"success\" = TRUE",
                Integer.class
        )).isEqualTo(2);
    }

    private void assertTimestampEqualsCreatedAt(JdbcTemplate jdbcTemplate, String column) {
        OffsetDateTime timestamp = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM device_commands WHERE command_id = 'legacy-command-001'",
                OffsetDateTime.class
        );
        assertThat(timestamp).isNotNull();
        assertThat(timestamp.toInstant()).isEqualTo(Instant.from(CREATED_AT));
    }

    private DataSource dataSource() {
        String databaseName = "device-command-migration-" + UUID.randomUUID();
        return new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
    }
}
