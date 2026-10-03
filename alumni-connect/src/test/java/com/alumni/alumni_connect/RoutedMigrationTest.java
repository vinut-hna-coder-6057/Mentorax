package com.alumni.alumni_connect;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutedMigrationTest {
    @Test
    void freshChainReceivesHardeningChangesAfterItsCurrentBaseline() throws Exception {
        verifyChain("classpath:db/fresh", "1", "11");
    }

    @Test
    void normalizedChainReceivesHardeningChangesAfterItsCurrentBaseline() throws Exception {
        verifyChain("classpath:db/normalized", "2", "11");
    }

    @Test
    void sharedOutboxMigrationCanFollowLegacyV9() throws Exception {
        String url = "jdbc:h2:mem:legacy_outbox_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/shared")
                .baselineOnMigrate(true)
                .baselineVersion("9")
                .load();

        var result = flyway.migrate();

        assertEquals(2, result.migrationsExecuted);
        assertEquals("11", flyway.info().current().getVersion().getVersion());
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertTrue(hasTable(connection, "email_outbox"));
            assertTrue(hasColumn(connection, "email_outbox", "purpose"));
        }
    }

    private static void verifyChain(String location, String baselineVersion, String expectedVersion) throws Exception {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, role VARCHAR(32), status VARCHAR(32))");
            statement.execute("CREATE TABLE alumni_profiles (user_id BIGINT PRIMARY KEY, approval_status VARCHAR(32))");
            statement.execute("CREATE TABLE otp_verifications (id BIGINT PRIMARY KEY, email VARCHAR(255), code_hash VARCHAR(255), purpose VARCHAR(64), attempt_count INT, consumed_at DATETIME, expiry DATETIME, verified BOOLEAN)");
            statement.execute("INSERT INTO users (id, role, status) VALUES (1, 'ALUMNI', 'APPROVED')");
            statement.execute("INSERT INTO alumni_profiles (user_id, approval_status) VALUES (1, 'PENDING')");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(location, "classpath:db/shared")
                .baselineOnMigrate(true)
                .baselineVersion(baselineVersion)
                .load();
        var result = flyway.migrate();
        assertEquals(4, result.migrationsExecuted);
        assertEquals(expectedVersion, flyway.info().current().getVersion().getVersion());

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertTrue(hasColumn(connection, "otp_verifications", "reset_token_hash"));
            assertTrue(hasColumn(connection, "otp_verifications", "reset_token_expiry"));
            assertTrue(hasTable(connection, "email_outbox"));
            assertTrue(hasColumn(connection, "email_outbox", "purpose"));
            try (var statement = connection.createStatement();
                 var approval = statement.executeQuery("SELECT approval_status FROM alumni_profiles WHERE user_id = 1")) {
                assertTrue(approval.next());
                assertEquals("APPROVED", approval.getString(1));
            }
        }
    }

    private static boolean hasTable(java.sql.Connection connection, String table) throws Exception {
        try (var tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, table.toUpperCase(java.util.Locale.ROOT), new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static boolean hasColumn(java.sql.Connection connection, String table, String column) throws Exception {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null,
                table.toUpperCase(java.util.Locale.ROOT), column.toUpperCase(java.util.Locale.ROOT))) {
            return columns.next();
        }
    }
}
