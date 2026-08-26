package com.animalguard.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class FlywayBaselineIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appliesAllMigrationsBeforeHibernateValidation() {
        assertThat(flyway.info().applied())
                .isNotEmpty()
                .allSatisfy(migration -> assertThat(migration.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM \"flyway_schema_history\" "
                        + "WHERE \"success\" = TRUE AND \"version\" IS NOT NULL",
                Long.class
        )).isEqualTo(Arrays.stream(flyway.info().applied())
                .filter(migration -> migration.getVersion() != null)
                .count());
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ANIMAL_OBSERVATION_STATES'",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = 'UK_ANIMAL_OBSERVATION_STATES_CAMERA_ID'",
                Long.class
        )).isEqualTo(1L);
    }
}
