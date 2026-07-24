package ru.itmo.nemat.vkconnector;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DatabaseMigrationIT {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.10-alpine3.23")
                    .withDatabaseName("vk_connector_db")
                    .withUsername("curator_user")
                    .withPassword("curator_password");

    @Test
    void flywayMigrationsApplyToCleanPostgres() throws Exception {
        MigrateResult result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        assertThat(result.migrationsExecuted).isGreaterThan(0);

        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );
             var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "select count(*) from flyway_schema_history where success = true"
             )) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(result.migrations.size());
        }
    }
}
