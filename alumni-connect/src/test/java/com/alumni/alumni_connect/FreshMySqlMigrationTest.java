package com.alumni.alumni_connect;

import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.junit.jupiter.api.Assumptions;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional real-MySQL migration and Hibernate validation; only targets an explicitly supplied empty schema. */
class FreshMySqlMigrationTest {
    private static final List<String> REQUIRED_TABLES = List.of(
            "users", "student_profiles", "alumni_profiles", "skills", "user_skills", "events",
            "event_registrations", "connections", "conversations", "conversation_participants",
            "messages", "notifications", "otp_verifications");

    @Test
    void freshMySqlSchemaMigratesAndPassesHibernateValidation() throws Exception {
        String url = System.getenv("MENTORAX_MYSQL_MIGRATION_TEST_URL");
        String username = System.getenv("MENTORAX_MYSQL_MIGRATION_TEST_USERNAME");
        String password = System.getenv("MENTORAX_MYSQL_MIGRATION_TEST_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank() && username != null && !username.isBlank() && password != null,
                "Set disposable MySQL test URL, username, and password to enable this integration test.");

        try (var connection = DriverManager.getConnection(url, username, password);
             var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            List<String> existing = new ArrayList<>();
            while (tables.next()) existing.add(tables.getString("TABLE_NAME"));
            assertTrue(existing.isEmpty(), "Refusing to run the fresh migration test against a non-empty database: " + existing);
        }

        try (ConfigurableApplicationContext ignored = startApplication(url, username, password)) {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                List<String> actual = new ArrayList<>();
                while (tables.next()) actual.add(tables.getString("TABLE_NAME").toLowerCase());
                assertTrue(actual.containsAll(REQUIRED_TABLES), "Fresh MySQL schema is missing required tables: " + REQUIRED_TABLES.stream().filter(t -> !actual.contains(t)).toList());
            }
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement();
                 var migrations = statement.executeQuery("SELECT version, checksum, success FROM flyway_schema_history WHERE version IN ('1', '2', '3') ORDER BY version")) {
                var checksums = new java.util.HashMap<String, Integer>();
                while (migrations.next()) {
                    assertTrue(migrations.getBoolean("success"), "Fresh migration did not succeed: " + migrations.getString("version"));
                    checksums.put(migrations.getString("version"), migrations.getInt("checksum"));
                }
                assertTrue(checksums.equals(java.util.Map.of(
                                "1", 744378093,
                                "2", -1002466783,
                                "3", 1514469376)),
                        "Fresh migration checksums differ from the recorded production chain: " + checksums);
                assertTrue(hasColumn(connection, "otp_verifications", "reset_token_hash"),
                        "Fresh schema is missing the reset-token hash column");
                assertTrue(hasColumn(connection, "otp_verifications", "reset_token_expiry"),
                        "Fresh schema is missing the reset-token expiry column");
                assertTrue(hasImportedKey(connection, "student_profiles", "user_id", "users"));
                assertTrue(hasImportedKey(connection, "alumni_profiles", "user_id", "users"));
                assertTrue(hasImportedKey(connection, "otp_verifications", "user_id", "users"));
                assertTrue(hasImportedKey(connection, "messages", "conversation_id", "conversations"));
                assertTrue(hasImportedKey(connection, "messages", "sender_id", "users"));
                assertTrue(hasImportedKey(connection, "notifications", "recipient_id", "users"));
                assertTrue(hasImportedKey(connection, "notifications", "actor_id", "users"));
                assertTrue(hasUniqueIndex(connection, "otp_verifications", "reset_token_hash"));
                assertTrue(hasUniqueIndex(connection, "conversations", "direct_user_low_id"));
                assertTrue(hasUniqueIndex(connection, "conversations", "direct_user_high_id"));
            }
        }
    }

    @Test
    void knownFailedLegacyV2AfterNormalizedV1IsRepairedAndMigrated() throws Exception {
        String url = System.getenv("MENTORAX_MYSQL_RECOVERY_TEST_URL");
        String username = System.getenv("MENTORAX_MYSQL_RECOVERY_TEST_USERNAME");
        String password = System.getenv("MENTORAX_MYSQL_RECOVERY_TEST_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank() && username != null && !username.isBlank() && password != null,
                "Set disposable MySQL recovery URL, username, and password to enable this integration test.");

        try (var connection = DriverManager.getConnection(url, username, password);
             var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            List<String> existing = new ArrayList<>();
            while (tables.next()) existing.add(tables.getString("TABLE_NAME"));
            assertTrue(existing.isEmpty(), "Refusing to prepare the recovery fixture in a non-empty database: " + existing);
        }

        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/normalized")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO flyway_schema_history
                        (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                    VALUES (2, '2', 'migrate legacy schema', 'SQL', 'V2__migrate_legacy_schema.sql',
                            -1728185645, CURRENT_USER(), 0, FALSE)
                    """);
        }

        try (ConfigurableApplicationContext ignored = startApplication(url, username, password);
             var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1 AND description = 'complete current baseline'")) {
            assertTrue(rows.next() && rows.getInt(1) == 1, "Normalized completion migration V2 was not applied");
            try (var failedRows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")) {
                assertTrue(failedRows.next() && failedRows.getInt(1) == 0, "Failed history rows remain after recovery");
            }
        }
    }

    private static boolean hasColumn(java.sql.Connection connection, String table, String column) throws Exception {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return columns.next();
        }
    }

    private static boolean hasImportedKey(java.sql.Connection connection, String table, String column, String targetTable)
            throws Exception {
        try (var keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && targetTable.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) return true;
            }
            return false;
        }
    }

    private static boolean hasUniqueIndex(java.sql.Connection connection, String table, String column) throws Exception {
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
            while (indexes.next()) {
                if (!indexes.getBoolean("NON_UNIQUE")
                        && column.equalsIgnoreCase(indexes.getString("COLUMN_NAME"))) return true;
            }
            return false;
        }
    }

    private static ConfigurableApplicationContext startApplication(String url, String username, String password) {
        return new SpringApplicationBuilder(AlumniConnectApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.main.banner-mode=off", "server.port=0")
                .run(
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password,
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                        "--jwt.secret=migration-test-secret-that-is-at-least-32-characters-long",
                        "--app.websocket.allowed-origins=http://localhost:*",
                        "--app.cors.allowed-origin-patterns=http://localhost:*");
    }
}
