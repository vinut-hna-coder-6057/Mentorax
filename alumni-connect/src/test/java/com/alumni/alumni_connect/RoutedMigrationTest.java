package com.alumni.alumni_connect;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutedMigrationTest {
    @Test
    void lifecycleMigrationDefaultsExistingEvidenceToActive() throws Exception {
        String url = "jdbc:h2:mem:evidence_lifecycle_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, role VARCHAR(32), status VARCHAR(32), email_verified BOOLEAN NOT NULL DEFAULT FALSE)");
            statement.execute("CREATE TABLE alumni_profiles (user_id BIGINT PRIMARY KEY, approval_status VARCHAR(32))");
            statement.execute("CREATE TABLE otp_verifications (id BIGINT PRIMARY KEY, email VARCHAR(255), code_hash VARCHAR(255), purpose VARCHAR(64), attempt_count INT, consumed_at DATETIME, expiry DATETIME, verified BOOLEAN)");
            statement.execute("CREATE TABLE events (id BIGINT PRIMARY KEY, attendee_count INT NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO users (id, role, status) VALUES (1, 'STUDENT', 'APPROVED')");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/fresh", "classpath:db/shared")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(MigrationVersion.fromVersion("19"))
                .load();
        flyway.migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO experience_claims
                        (id, user_id, company, job_title, summary, created_at, updated_at, evidence_version)
                    VALUES (1, 1, 'Northstar', 'Engineer', 'Built systems', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
                    """);
            statement.execute("""
                    INSERT INTO experience_claim_evidence (
                        claim_id, evidence_version, evidence_storage_key, encrypted_evidence,
                        evidence_file_name, evidence_media_type, evidence_size, evidence_hash, created_at
                    ) VALUES (
                        1, 1, 'storage-key', X'01', 'certificate.pdf', 'application/pdf', 1, 'test-hash',
                        CURRENT_TIMESTAMP
                    )
                    """);
        }

        flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/fresh", "classpath:db/shared")
                .load();
        flyway.migrate();

        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT evidence_status, relationship_type, related_evidence_id "
                             + "FROM experience_claim_evidence WHERE claim_id = 1")) {
            assertTrue(result.next());
            assertEquals("ACTIVE", result.getString("evidence_status"));
            assertNull(result.getString("relationship_type"));
            assertNull(result.getObject("related_evidence_id"));
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            assertTrue(hasTable(connection, "experience_evidence_extraction"));
            assertTrue(hasTable(connection, "experience_evidence_extraction_field"));
            assertTrue(hasTable(connection, "experience_evidence_extraction_candidate"));
            assertTrue(hasTable(connection, "experience_evidence_extraction_statement"));
            assertTrue(hasTable(connection, "experience_evidence_extraction_provenance"));
            assertTrue(hasTable(connection, "experience_evidence_extraction_warning"));
            assertTrue(hasUniqueIndex(connection, "experience_claim_evidence", "id", "evidence_version"));
            assertTrue(hasImportedKey(connection, "experience_evidence_extraction",
                    "evidence_id", "experience_claim_evidence"));
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT claim_type FROM experience_claims WHERE id = 1")) {
            assertTrue(result.next());
            assertEquals("UNSPECIFIED", result.getString("claim_type"));
        }
    }

    @Test
    void freshChainReceivesHardeningChangesAfterItsCurrentBaseline() throws Exception {
        verifyChain("classpath:db/fresh", "1", "26");
    }

    @Test
    void normalizedChainReceivesHardeningChangesAfterItsCurrentBaseline() throws Exception {
        verifyChain("classpath:db/normalized", "2", "26");
    }

    @Test
    void sharedOutboxMigrationCanFollowLegacyV9() throws Exception {
        String url = "jdbc:h2:mem:legacy_outbox_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, role VARCHAR(32), status VARCHAR(32), email_verified BOOLEAN NOT NULL DEFAULT FALSE)");
            statement.execute("CREATE TABLE events (id BIGINT PRIMARY KEY, attendee_count INT NOT NULL DEFAULT 0)");
            statement.execute("INSERT INTO users (id, email_verified) VALUES (1, FALSE)");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/shared")
                .baselineOnMigrate(true)
                .baselineVersion("9")
                .load();

        var result = flyway.migrate();

        assertEquals(17, result.migrationsExecuted);
        assertEquals("26", flyway.info().current().getVersion().getVersion());
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertTrue(hasTable(connection, "email_outbox"));
            assertTrue(hasColumn(connection, "email_outbox", "purpose"));
            assertTrue(hasColumn(connection, "events", "max_capacity"));
            assertExperienceEvidenceSchema(connection);
            assertTrue(hasTable(connection, "experience_corroboration_attempts"));
            assertKnowledgeContributionSchema(connection);
            assertTrue(hasTable(connection, "knowledge_companion_conversations"));
            assertTrue(hasTable(connection, "knowledge_question_interest"));
            assertTrue(hasTable(connection, "knowledge_roadmaps"));
            assertTrue(hasTable(connection, "knowledge_shared_answer_cache"));
            assertTrue(hasTable(connection, "knowledge_shared_answer_cache_source"));
            assertTrue(hasTable(connection, "knowledge_shared_answer_cache_lock"));
            try (var statement = connection.createStatement();
                 var resultSet = statement.executeQuery(
                         "SELECT email_verified FROM users WHERE id = 1")) {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getBoolean(1));
            }
        }
    }

    private static void verifyChain(String location, String baselineVersion, String expectedVersion) throws Exception {
        String url = "jdbc:h2:mem:migration_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, role VARCHAR(32), status VARCHAR(32), email_verified BOOLEAN NOT NULL DEFAULT FALSE)");
            statement.execute("CREATE TABLE alumni_profiles (user_id BIGINT PRIMARY KEY, approval_status VARCHAR(32))");
            statement.execute("CREATE TABLE otp_verifications (id BIGINT PRIMARY KEY, email VARCHAR(255), code_hash VARCHAR(255), purpose VARCHAR(64), attempt_count INT, consumed_at DATETIME, expiry DATETIME, verified BOOLEAN)");
            statement.execute("CREATE TABLE events (id BIGINT PRIMARY KEY, attendee_count INT NOT NULL DEFAULT 0)");
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
        assertEquals(19, result.migrationsExecuted);
        assertEquals(expectedVersion, flyway.info().current().getVersion().getVersion());

        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            assertTrue(hasColumn(connection, "otp_verifications", "reset_token_hash"));
            assertTrue(hasColumn(connection, "otp_verifications", "reset_token_expiry"));
            assertTrue(hasTable(connection, "email_outbox"));
            assertTrue(hasColumn(connection, "email_outbox", "purpose"));
            assertTrue(hasColumn(connection, "events", "max_capacity"));
            assertTrue(hasIndex(connection, "users", "idx_users_role_status_id"));
            assertExperienceEvidenceSchema(connection);
            assertExperienceExtractionSchema(connection);
            assertTrue(hasTable(connection, "experience_corroboration_attempts"));
            assertKnowledgeContributionSchema(connection);
            assertTrue(hasTable(connection, "knowledge_companion_conversations"));
            assertTrue(hasTable(connection, "knowledge_question_interest"));
            assertTrue(hasTable(connection, "knowledge_roadmaps"));
            assertTrue(hasColumn(connection, "knowledge_contributions", "reminder_due_at"));
            assertTrue(hasColumn(connection, "experience_claim_processing", "lease_token"));
            assertTrue(hasColumn(connection, "experience_claims", "analysis_method"));
            assertTrue(hasColumn(connection, "experience_claims", "ocr_status"));
            assertTrue(hasColumn(connection, "experience_claims", "claim_type"));
            try (var statement = connection.createStatement();
                 var approval = statement.executeQuery("SELECT approval_status FROM alumni_profiles WHERE user_id = 1")) {
                assertTrue(approval.next());
                assertEquals("APPROVED", approval.getString(1));
            }
            try (var statement = connection.createStatement();
                 var verified = statement.executeQuery("SELECT email_verified FROM users WHERE id = 1")) {
                assertTrue(verified.next());
                assertTrue(verified.getBoolean(1));
            }
        }
    }

    private static void assertKnowledgeContributionSchema(java.sql.Connection connection) throws Exception {
        assertTrue(hasTable(connection, "knowledge_contributions"));
        assertTrue(hasTable(connection, "knowledge_contribution_versions"));
        assertTrue(hasTable(connection, "knowledge_contribution_entries"));
        assertTrue(hasTable(connection, "knowledge_contribution_audit"));
        assertTrue(hasImportedKey(connection, "knowledge_contributions", "owner_user_id", "users"));
        assertTrue(hasImportedKey(connection, "knowledge_contribution_versions", "contribution_id", "knowledge_contributions"));
        assertTrue(hasIndex(connection, "knowledge_contributions", "idx_knowledge_contributions_public"));
        assertTrue(hasIndex(connection, "knowledge_contribution_entries", "idx_knowledge_entries_version_position"));
    }

    private static void assertExperienceEvidenceSchema(java.sql.Connection connection) throws Exception {
        assertTrue(hasColumn(connection, "experience_claims", "analysis_method"));
        assertTrue(hasColumn(connection, "experience_claims", "ocr_status"));
        assertTrue(hasColumn(connection, "experience_claims", "claim_type"));
        assertTrue(hasColumn(connection, "experience_claims", "request_id"));
        assertTrue(hasColumn(connection, "experience_claims", "evidence_storage_key"));
        assertTrue(hasColumn(connection, "experience_claims", "encrypted_evidence"));
        assertTrue(hasColumn(connection, "experience_claims", "evidence_file_name"));
        assertTrue(hasColumn(connection, "experience_claims", "evidence_media_type"));
        assertTrue(hasColumn(connection, "experience_claims", "evidence_size"));
        assertTrue(hasColumn(connection, "experience_claims", "evidence_uploaded_at"));
        assertTrue(List.of(java.sql.Types.BLOB, java.sql.Types.LONGVARBINARY)
                        .contains(columnDataType(connection, "experience_claims", "encrypted_evidence")),
                "Expected encrypted evidence to be stored as a large binary column "
                        + "(H2 reports BLOB, MySQL reports LONGVARBINARY), but found "
                        + columnDataType(connection, "experience_claims", "encrypted_evidence"));
        assertTrue(hasTable(connection, "experience_claim_audit"));
        assertTrue(hasColumn(connection, "experience_claim_evidence", "evidence_status"));
        assertTrue(hasColumn(connection, "experience_claim_evidence", "relationship_type"));
        assertTrue(hasColumn(connection, "experience_claim_evidence", "related_evidence_id"));
        assertTrue(hasColumn(connection, "experience_claim_evidence", "superseded_at"));
        assertTrue(hasIndex(connection, "experience_claim_evidence",
                "idx_experience_claim_evidence_lifecycle"));
        assertTrue(hasImportedKey(connection, "experience_claim_evidence", "related_evidence_id",
                "experience_claim_evidence"));
        assertTrue(hasIndex(connection, "experience_claims", "uk_experience_claim_user_request"));
        assertTrue(hasIndex(connection, "experience_claim_audit", "idx_experience_claim_audit_claim_created"));
    }

    private static void assertExperienceExtractionSchema(java.sql.Connection connection) throws Exception {
        assertTrue(hasTable(connection, "experience_evidence_extraction"));
        assertTrue(hasTable(connection, "experience_evidence_extraction_field"));
        assertTrue(hasTable(connection, "experience_evidence_extraction_candidate"));
        assertTrue(hasTable(connection, "experience_evidence_extraction_statement"));
        assertTrue(hasTable(connection, "experience_evidence_extraction_provenance"));
        assertTrue(hasTable(connection, "experience_evidence_extraction_warning"));
        assertTrue(hasColumn(connection, "experience_evidence_extraction", "evidence_id"));
        assertTrue(hasColumn(connection, "experience_evidence_extraction", "evidence_version"));
        assertTrue(hasColumn(connection, "experience_evidence_extraction", "response_hash"));
        assertTrue(hasColumn(connection, "experience_evidence_extraction_provenance", "page_number"));
        assertTrue(hasColumn(connection, "experience_evidence_extraction_provenance", "text_excerpt"));
        assertTrue(hasColumn(connection, "experience_evidence_extraction_provenance", "bounding_box_x"));
        assertTrue(hasIndex(connection, "experience_evidence_extraction", "idx_experience_extraction_history"));
        assertTrue(hasImportedKey(connection, "experience_evidence_extraction",
                "evidence_id", "experience_claim_evidence"));
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

    private static int columnDataType(
            java.sql.Connection connection, String table, String column) throws Exception {
        try (var columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null,
                table.toUpperCase(java.util.Locale.ROOT),
                column.toUpperCase(java.util.Locale.ROOT))) {
            if (!columns.next()) throw new AssertionError("Missing column " + table + "." + column);
            return columns.getInt("DATA_TYPE");
        }
    }

    private static boolean hasIndex(java.sql.Connection connection, String table, String expectedIndex)
            throws Exception {
        try (var indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, table.toUpperCase(java.util.Locale.ROOT), false, false)) {
            while (indexes.next()) {
                if (expectedIndex.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            }
            return false;
        }
    }

    private static boolean hasUniqueIndex(
            java.sql.Connection connection, String table, String... columns) throws Exception {
        java.util.Map<String, java.util.Set<String>> indexes = new java.util.HashMap<>();
        try (var rows = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, table.toUpperCase(java.util.Locale.ROOT), true, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    indexes.computeIfAbsent(name, ignored -> new java.util.HashSet<>())
                            .add(column.toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        java.util.Set<String> expected = java.util.Arrays.stream(columns)
                .map(column -> column.toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return indexes.values().stream().anyMatch(expected::equals);
    }

    private static boolean hasImportedKey(
            java.sql.Connection connection, String table, String column, String targetTable) throws Exception {
        try (var keys = connection.getMetaData().getImportedKeys(connection.getCatalog(), null,
                table.toUpperCase(java.util.Locale.ROOT))) {
            while (keys.next()) {
                if (column.equalsIgnoreCase(keys.getString("FKCOLUMN_NAME"))
                        && targetTable.equalsIgnoreCase(keys.getString("PKTABLE_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
