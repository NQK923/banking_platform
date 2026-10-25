package com.ewallet.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@org.junit.jupiter.api.Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class FlywayMigrationCompatibilityTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("banking_platform_migration_test")
        .withUsername("banking")
        .withPassword("banking");

    @Test
    void migratesAnExistingV1DatabaseToLatestWithoutLosingWalletData() throws Exception {
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("1"))
            .load()
            .migrate();

        UUID userId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement(
                 "INSERT INTO users (id, email, password_hash, pin_hash, roles, status, created_at) VALUES (?, ?, ?, ?, 'ROLE_USER', 'ACTIVE', now())"
             )) {
            statement.setObject(1, userId);
            statement.setString(2, "migration-user@example.test");
            statement.setString(3, "legacy-password-hash");
            statement.setString(4, "legacy-pin-hash");
            statement.executeUpdate();
        }

        Flyway latest = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .load();
        latest.migrate();

        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("6");
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            assertThat(queryCount(connection, "SELECT count(*) FROM users WHERE id = '" + userId + "'"))
                .isEqualTo(1);
            assertThat(queryCount(connection, "SELECT count(*) FROM accounts WHERE account_kind = 'SYSTEM'"))
                .isEqualTo(3);
            assertThat(queryCount(connection, "SELECT count(*) FROM information_schema.tables WHERE table_name IN ('ai_risk_evaluations', 'support_chat_sessions', 'password_reset_otps', 'wallet_operation_idempotency')"))
                .isEqualTo(4);
        }
    }

    private int queryCount(java.sql.Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
