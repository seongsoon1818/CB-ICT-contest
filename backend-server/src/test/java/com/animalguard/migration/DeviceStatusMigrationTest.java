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

class DeviceStatusMigrationTest {

    private static final OffsetDateTime LAST_SEEN = OffsetDateTime.parse("2026-08-26T00:00:00Z");

    @Test
    void backfillsLegacyRowsWithoutAddingADeviceIdUniqueConstraint() {
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
                "INSERT INTO device_statuses (device_id, connected, last_seen, temperature) VALUES (?, ?, ?, ?)",
                "pi-001",
                true,
                LAST_SEEN,
                21.5
        );
        jdbcTemplate.update(
                "INSERT INTO device_statuses (device_id, connected, last_seen, temperature) VALUES (?, ?, ?, ?)",
                "pi-001",
                false,
                null,
                null
        );

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT operational_status FROM device_statuses WHERE last_seen IS NOT NULL",
                String.class
        )).isEqualTo("ONLINE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT operational_status FROM device_statuses WHERE last_seen IS NULL",
                String.class
        )).isEqualTo("OFFLINE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reported_at FROM device_statuses WHERE last_seen IS NOT NULL",
                OffsetDateTime.class
        ).toInstant()).isEqualTo(Instant.from(LAST_SEEN));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT received_at FROM device_statuses WHERE last_seen IS NOT NULL",
                OffsetDateTime.class
        ).toInstant()).isEqualTo(Instant.from(LAST_SEEN));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_statuses WHERE received_at IS NULL",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM device_statuses WHERE device_id = 'pi-001'",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'DEVICE_STATUSES' AND CONSTRAINT_TYPE = 'UNIQUE'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" "
                        + "WHERE \"version\" = '5' AND \"success\" = TRUE",
                Integer.class
        )).isEqualTo(1);
    }

    private DataSource dataSource() {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:device-status-migration-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
    }
}
