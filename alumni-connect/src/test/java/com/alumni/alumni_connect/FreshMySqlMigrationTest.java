package com.alumni.alumni_connect;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.sql.Connection;
import java.sql.DriverManager;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL migration and Hibernate validation; only targets explicitly supplied disposable schemas. */
class FreshMySqlMigrationTest {
    private static final List<String> REQUIRED_TABLES = List.of(
            "users", "student_profiles", "alumni_profiles", "skills", "user_skills", "events",
            "event_registrations", "connections", "conversations", "conversation_participants",
            "messages", "notifications", "otp_verifications", "email_outbox",
            "experience_claim_audit", "experience_claim_evidence", "experience_claim_processing",
            "experience_corroboration_attempts");
    private static final List<String> FRESH_HISTORY =
            List.of("1", "2", "3", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
    private static final List<String> NORMALIZED_HISTORY =
            List.of("1", "2", "3", "4", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
    private static final String MIGRATION_TEST_PREFIX = "MENTORAX_MYSQL_MIGRATION_TEST";
    private static final String RECOVERY_TEST_PREFIX = "MENTORAX_MYSQL_RECOVERY_TEST";

    @Test
    void freshMySqlSchemaMigratesAndPassesHibernateValidation() throws Exception {
        DatabaseSettings database = settings(MIGRATION_TEST_PREFIX);
        assertEmptySchema(database);

        try (ConfigurableApplicationContext ignored =
                     startApplication(database.url(), database.username(), database.password());
             Connection connection = DriverManager.getConnection(
                     database.url(), database.username(), database.password())) {
            assertConnectedToExpectedSchema(connection, database);
            assertTables(connection);
            assertMigrationHistory(connection, FRESH_HISTORY);
            assertFreshChecksums(connection);
            assertFinalSchema(connection);
        }
    }

    @Test
    void knownFailedLegacyV2AfterNormalizedV1IsRepairedAndMigrated() throws Exception {
        DatabaseSettings database = settings(RECOVERY_TEST_PREFIX);
        assertEmptySchema(database);

        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/normalized")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                database.url(), database.username(), database.password());
             var statement = connection.createStatement()) {
            assertConnectedToExpectedSchema(connection, database);
            statement.executeUpdate("""
                    INSERT INTO flyway_schema_history
                        (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                    VALUES (2, '2', 'migrate legacy schema', 'SQL', 'V2__migrate_legacy_schema.sql',
                            -1728185645, CURRENT_USER(), 0, FALSE)
                    """);
        }

        try (ConfigurableApplicationContext ignored =
                     startApplication(database.url(), database.username(), database.password());
             Connection connection = DriverManager.getConnection(
                     database.url(), database.username(), database.password())) {
            assertConnectedToExpectedSchema(connection, database);
            assertMigrationHistory(connection, NORMALIZED_HISTORY);
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version = '2' AND success = 1 AND description = 'complete current baseline'"));
            assertEquals(0, count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0"));
            assertFinalSchema(connection);
        }
    }

    private static DatabaseSettings settings(String prefix) {
        return settings(prefix, System.getenv());
    }

    static DatabaseSettings settings(String prefix, Map<String, String> environment) {
        String expectedDatabase = switch (prefix) {
            case MIGRATION_TEST_PREFIX -> "mentorax_migration_test";
            case RECOVERY_TEST_PREFIX -> "mentorax_recovery_test";
            default -> throw new IllegalArgumentException("Unsupported MySQL migration-test configuration");
        };
        String url = environment.get(prefix + "_URL");
        String username = environment.get(prefix + "_USERNAME");
        String password = environment.get(prefix + "_PASSWORD");
        if (isBlank(url) || isBlank(username) || isBlank(password)) {
            throw new IllegalStateException("MySQL migration-test URL, username, and password are required for "
                    + prefix + "; tests will not run without explicit credentials.");
        }
        if (!url.startsWith("jdbc:mysql://")) {
            throw invalidTarget(prefix, expectedDatabase);
        }

        URI endpoint;
        try {
            endpoint = URI.create(url.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw invalidTarget(prefix, expectedDatabase);
        }
        String host = endpoint.getHost();
        boolean loopbackHost = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
        if (!"mysql".equalsIgnoreCase(endpoint.getScheme())
                || !loopbackHost
                || endpoint.getPort() != 3306
                || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || !("/" + expectedDatabase).equals(endpoint.getPath())) {
            throw invalidTarget(prefix, expectedDatabase);
        }
        return new DatabaseSettings(url, username, password, expectedDatabase);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalidTarget(String prefix, String expectedDatabase) {
        return new IllegalArgumentException(prefix + " must target only jdbc:mysql://localhost:3306/"
                + expectedDatabase + " (or an equivalent loopback address), without URL credentials or options.");
    }

    private static void assertEmptySchema(DatabaseSettings database) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.username(), database.password())) {
            assertConnectedToExpectedSchema(connection, database);
            try (var tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                List<String> existing = new ArrayList<>();
                while (tables.next()) existing.add(tables.getString("TABLE_NAME"));
                assertTrue(existing.isEmpty(),
                        "Refusing to run a MySQL migration test against a non-empty database: " + existing);
            }
        }
    }

    private static void assertConnectedToExpectedSchema(Connection connection, DatabaseSettings database)
            throws Exception {
        assertEquals(database.schema(), connection.getCatalog(),
                "MySQL connection did not select its dedicated migration-test schema");
    }

    private static void assertTables(Connection connection) throws Exception {
        Set<String> actual = new HashSet<>();
        try (var tables = connection.getMetaData().getTables(
                connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) actual.add(tables.getString("TABLE_NAME").toLowerCase(java.util.Locale.ROOT));
        }
        assertTrue(actual.containsAll(REQUIRED_TABLES),
                "MySQL schema is missing required tables: "
                        + REQUIRED_TABLES.stream().filter(table -> !actual.contains(table)).toList());
    }

    private static void assertMigrationHistory(Connection connection, List<String> expectedVersions) throws Exception {
        List<String> versions = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                assertTrue(rows.getBoolean("success"), "Migration did not succeed: " + rows.getString("version"));
                versions.add(rows.getString("version"));
            }
        }
        assertEquals(expectedVersions, versions, "Unexpected or incomplete MySQL migration chain");
        for (String version : List.of("17", "18", "19")) {
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM flyway_schema_history "
                            + "WHERE version = '" + version + "' AND success = 1 "
                            + "AND type = 'SQL' AND checksum IS NOT NULL"),
                    "Migration V" + version + " must be recorded as a successful checksummed SQL migration");
        }
    }

    private static void assertFreshChecksums(Connection connection) throws Exception {
        Map<String, Integer> checksums = new HashMap<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(
                     "SELECT version, checksum FROM flyway_schema_history WHERE version IN ('1', '2', '3')")) {
            while (rows.next()) checksums.put(rows.getString("version"), rows.getInt("checksum"));
        }
        assertEquals(Map.of(
                        "1", 744378093,
                        "2", -1002466783,
                        "3", 1514469376),
                checksums, "Fresh migration checksums differ from the recorded production chain");
    }

    private static void assertFinalSchema(Connection connection) throws Exception {
        assertTables(connection);
        assertMediumBlobEvidenceColumn(connection);
        for (String[] column : List.of(
                new String[]{"email_outbox", "recipient"},
                new String[]{"email_outbox", "encrypted_otp"},
                new String[]{"email_outbox", "purpose"},
                new String[]{"email_outbox", "lease_until"},
                new String[]{"email_outbox", "expires_at"},
                new String[]{"events", "max_capacity"},
                new String[]{"otp_verifications", "reset_token_hash"},
                new String[]{"otp_verifications", "reset_token_expiry"},
                new String[]{"experience_claims", "evidence_version"},
                new String[]{"experience_claims", "decision_explanation"},
                new String[]{"experience_claims", "requested_evidence"},
                new String[]{"experience_claims", "decision_at"},
                new String[]{"experience_claims", "corroboration_status"},
                new String[]{"experience_claims", "analysis_method"},
                new String[]{"experience_claims", "ocr_status"},
                new String[]{"experience_claim_evidence", "evidence_version"},
                new String[]{"experience_claim_evidence", "encrypted_evidence"},
                new String[]{"experience_claim_processing", "next_attempt_at"},
                new String[]{"experience_corroboration_attempts", "independently_authenticated"})) {
            assertTrue(hasColumn(connection, column[0], column[1]),
                    "Missing MySQL column " + column[0] + "." + column[1]);
        }

        for (String[] foreignKey : List.of(
                new String[]{"student_profiles", "user_id", "users"},
                new String[]{"alumni_profiles", "user_id", "users"},
                new String[]{"event_registrations", "event_id", "events"},
                new String[]{"event_registrations", "user_id", "users"},
                new String[]{"conversation_participants", "conversation_id", "conversations"},
                new String[]{"conversation_participants", "user_id", "users"},
                new String[]{"messages", "conversation_id", "conversations"},
                new String[]{"messages", "sender_id", "users"},
                new String[]{"notifications", "recipient_id", "users"},
                new String[]{"notifications", "actor_id", "users"},
                new String[]{"otp_verifications", "user_id", "users"},
                new String[]{"experience_claim_evidence", "claim_id", "experience_claims"},
                new String[]{"experience_claim_processing", "claim_id", "experience_claims"},
                new String[]{"experience_corroboration_attempts", "claim_id", "experience_claims"})) {
            assertTrue(hasImportedKey(connection, foreignKey[0], foreignKey[1], foreignKey[2]),
                    "Missing MySQL foreign key " + foreignKey[0] + "." + foreignKey[1]);
        }

        assertTrue(hasUniqueIndex(connection, "users", "email"));
        assertTrue(hasUniqueIndex(connection, "student_profiles", "roll_number"));
        assertTrue(hasUniqueIndex(connection, "event_registrations", "event_id", "user_id"));
        assertTrue(hasUniqueIndex(connection, "connections", "pair_low_id", "pair_high_id"));
        assertTrue(hasUniqueIndex(connection, "conversation_participants", "conversation_id", "user_id"));
        assertTrue(hasUniqueIndex(connection, "conversations", "direct_user_low_id", "direct_user_high_id"));
        assertTrue(hasUniqueIndex(connection, "otp_verifications", "reset_token_hash"));
        assertTrue(hasUniqueIndex(connection, "experience_claim_evidence", "claim_id", "evidence_version"));
        assertTrue(hasUniqueIndex(connection, "experience_claim_evidence", "claim_id", "evidence_hash"));

        assertTrue(hasIndex(connection, "users", "idx_users_role_status_id"));
        assertTrue(hasIndex(connection, "email_outbox", "idx_email_outbox_due"));
        assertTrue(hasIndex(connection, "email_outbox", "idx_email_outbox_expiry"));
        assertTrue(hasIndex(connection, "email_outbox", "idx_email_outbox_recipient_purpose"));
        assertTrue(hasCheckConstraint(connection, "events", "chk_event_capacity"));
        assertTrue(isNotNullable(connection, "email_outbox", "encrypted_otp"));
        assertTrue(isNullable(connection, "events", "max_capacity"),
                "Event capacity must remain optional for existing unlimited events");
    }

    private static void assertMediumBlobEvidenceColumn(Connection connection) throws Exception {
        try (var columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, "experience_claims", "encrypted_evidence")) {
            assertTrue(columns.next(), "Missing MySQL column experience_claims.encrypted_evidence");
            assertEquals("MEDIUMBLOB", columns.getString("TYPE_NAME").toUpperCase(java.util.Locale.ROOT));
            assertEquals(java.sql.Types.LONGVARBINARY, columns.getInt("DATA_TYPE"));
            assertEquals(16_777_215, columns.getInt("COLUMN_SIZE"),
                    "encrypted_evidence must preserve MySQL MEDIUMBLOB capacity");
        }
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return columns.next();
        }
    }

    private static boolean hasImportedKey(Connection connection, String table, String column, String targetTable)
            throws Exception {
        try (var keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), null, table)) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && targetTable.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) return true;
            }
            return false;
        }
    }

    private static boolean hasUniqueIndex(Connection connection, String table, String... columns) throws Exception {
        Map<String, Set<String>> uniqueIndexes = new HashMap<>();
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (name != null && column != null && !indexes.getBoolean("NON_UNIQUE")) {
                    uniqueIndexes.computeIfAbsent(name, ignored -> new HashSet<>())
                            .add(column.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        Set<String> expected = java.util.Arrays.stream(columns)
                .map(column -> column.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return uniqueIndexes.values().stream().anyMatch(expected::equals);
    }

    private static boolean hasIndex(Connection connection, String table, String indexName) throws Exception {
        try (var indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }

    private static boolean hasCheckConstraint(Connection connection, String table, String constraint) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema = DATABASE() AND table_name = ? "
                + "AND constraint_name = ? AND constraint_type = 'CHECK'";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (var rows = statement.executeQuery()) {
                return rows.next() && rows.getInt(1) == 1;
            }
        }
    }

    private static boolean isNotNullable(Connection connection, String table, String column) throws Exception {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return columns.next() && columns.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNoNulls;
        }
    }

    private static boolean isNullable(Connection connection, String table, String column) throws Exception {
        try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return columns.next() && columns.getInt("NULLABLE") == java.sql.DatabaseMetaData.columnNullable;
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

    record DatabaseSettings(String url, String username, String password, String schema) {}
}
