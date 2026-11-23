package com.alumni.alumni_connect;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import com.alumni.alumni_connect.service.KnowledgeAnswerCacheStore;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import com.alumni.alumni_connect.service.JdbcKnowledgeAnswerCacheStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.sql.Connection;
import java.sql.DriverManager;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-MySQL migration and Hibernate validation; only targets explicitly supplied disposable schemas. */
class FreshMySqlMigrationTest {
    private static final List<String> REQUIRED_TABLES = List.of(
            "users", "student_profiles", "alumni_profiles", "skills", "user_skills", "events",
            "event_registrations", "connections", "conversations", "conversation_participants",
            "messages", "notifications", "otp_verifications", "email_outbox",
            "experience_claim_audit", "experience_claim_evidence", "experience_claim_processing",
            "experience_corroboration_attempts", "experience_evidence_extraction",
            "experience_evidence_extraction_field", "experience_evidence_extraction_candidate",
            "experience_evidence_extraction_statement", "experience_evidence_extraction_provenance",
            "experience_evidence_extraction_warning", "knowledge_contributions",
            "knowledge_contribution_versions", "knowledge_contribution_entries", "knowledge_contribution_audit",
            "knowledge_companion_conversations", "knowledge_companion_messages", "knowledge_companion_message_sources",
            "knowledge_question_interest", "knowledge_roadmaps", "knowledge_roadmap_items", "knowledge_roadmap_item_sources",
            "knowledge_shared_answer_cache", "knowledge_shared_answer_cache_source", "knowledge_shared_answer_cache_lock");
    private static final List<String> FRESH_HISTORY =
            List.of("1", "2", "3", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26");
    private static final List<String> NORMALIZED_HISTORY =
            List.of("1", "2", "3", "4", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26");
    private static final String MIGRATION_TEST_PREFIX = "MENTORAX_MYSQL_MIGRATION_TEST";
    private static final String RECOVERY_TEST_PREFIX = "MENTORAX_MYSQL_RECOVERY_TEST";

    @Test
    void freshMySqlSchemaMigratesAndPassesHibernateValidation() throws Exception {
        DatabaseSettings database = settings(MIGRATION_TEST_PREFIX);
        assertEmptySchema(database);
        migrateFreshSchemaThrough21(database);
        seedExistingEvidence(database);
        Flyway.configure()
                .dataSource(verifiedJdbcUrl(database), database.username(), database.password())
                .locations("classpath:db/fresh", "classpath:db/shared")
                .load()
                .migrate();
        seedKnowledgeCacheSource(database);

        try (ConfigurableApplicationContext ignored =
                     startApplication(database.url(), database.username(), database.password());
             Connection connection = DriverManager.getConnection(
                     verifiedJdbcUrl(database), database.username(), database.password())) {
            assertConnectedToExpectedSchema(connection, database);
            assertTables(connection);
            assertMigrationHistory(connection, FRESH_HISTORY);
            assertFreshChecksums(connection);
            assertFinalSchema(connection);
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM experience_claim_evidence "
                            + "WHERE id = 1001 AND evidence_status = 'ACTIVE'"));
            assertEquals(1, count(connection,
                    "SELECT COUNT(*) FROM experience_claims "
                            + "WHERE id = 1001 AND claim_type = 'UNSPECIFIED'"));
            assertCacheStoreCleanup(database, ignored, connection);
        }
    }

    private static void assertCacheStoreCleanup(DatabaseSettings database, ApplicationContext application,
                                                Connection connection) throws Exception {
        try (Connection applicationConnection = application.getBean(javax.sql.DataSource.class).getConnection();
             var statement = applicationConnection.createStatement();
             var rows = statement.executeQuery("SELECT @@session.time_zone")) {
            assertTrue(rows.next());
            assertEquals("+00:00", rows.getString(1), "Application MySQL sessions must use UTC");
        }
        KnowledgeAnswerCacheStore cache = application.getBean(KnowledgeAnswerCacheStore.class);
        assertIndependentMySqlCacheClients(application, cache, connection);
        String expiredKey = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String expiredLease = UUID.randomUUID().toString();
        assertTrue(cache.tryAcquire(expiredKey, expiredLease, Duration.ofSeconds(60)));
        assertTrue(cache.save(new KnowledgeAnswerCacheStore.Entry(expiredKey, 1, "INTERVIEW_PREPARATION",
                "INTERVIEW_PREPARATION", "Grounded answer [1001:1]", "ALUMNI_REPORTED_EVIDENCE",
                "OPENAI_GROUNDED", "openai", "gpt-4.1-mini", "grounded-answer-v1", List.of(),
                LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)), expiredLease));

        cache.cleanupExpired();

        assertEquals(0, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache WHERE cache_key='" + expiredKey + "'"));
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache_lock WHERE cache_key='" + expiredKey + "'"));
        cache.release(expiredKey, expiredLease);

        String activeKey = UUID.randomUUID().toString().replace("-", "").repeat(2);
        String activeLease = UUID.randomUUID().toString();
        assertTrue(cache.tryAcquire(activeKey, activeLease, Duration.ofSeconds(60)));
        assertTrue(cache.save(new KnowledgeAnswerCacheStore.Entry(activeKey, 1, "INTERVIEW_PREPARATION",
                "INTERVIEW_PREPARATION", "Grounded answer [1001:1]", "ALUMNI_REPORTED_EVIDENCE",
                "OPENAI_GROUNDED", "openai", "gpt-4.1-mini", "grounded-answer-v1",
                List.of(new KnowledgeAnswerCacheStore.SourceRef(2001, 1)),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1)), activeLease));
        cache.cleanupExpired();
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache WHERE cache_key='" + activeKey + "'"));
        assertEquals("Grounded answer [1001:1]", cache.find(activeKey).orElseThrow().answer());

        cache.invalidateContribution(2001, "consent_revoked");
        assertEquals(1, count(connection, "SELECT COUNT(*) FROM knowledge_shared_answer_cache "
                + "WHERE cache_key='" + activeKey + "' AND cache_status='INVALIDATED' "
                + "AND invalidation_reason='consent_revoked'"));
        cache.cleanupExpired();
        assertEquals(0, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache WHERE cache_key='" + activeKey + "'"));
        try (var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE knowledge_shared_answer_cache_lock "
                    + "SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE cache_key='"
                    + activeKey + "'");
        }
        cache.cleanupExpired();
        assertEquals(0, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache_lock WHERE cache_key='" + activeKey + "'"));

        insertExpiredCacheBacklog(connection, 5_001);
        cache.cleanupExpired();
        assertEquals(1, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache WHERE cache_status='EXPIRED' "
                        + "AND expires_at<=CURRENT_TIMESTAMP(6)"),
                "One cleanup run must stop after its ten 500-row batches");
        cache.cleanupExpired();
        assertEquals(0, count(connection,
                "SELECT COUNT(*) FROM knowledge_shared_answer_cache WHERE cache_status='EXPIRED' "
                        + "AND expires_at<=CURRENT_TIMESTAMP(6)"),
                "A later cleanup run must drain the remaining backlog");
    }

    private static void assertIndependentMySqlCacheClients(ApplicationContext application,
                                                            KnowledgeAnswerCacheStore springStore,
                                                            Connection connection) throws Exception {
        var dataSource = application.getBean(javax.sql.DataSource.class);
        var first = new JdbcKnowledgeAnswerCacheStore(new JdbcTemplate(dataSource), new SimpleMeterRegistry());
        var second = new JdbcKnowledgeAnswerCacheStore(new JdbcTemplate(dataSource), new SimpleMeterRegistry());
        String concurrentKey = "f".repeat(64);
        String firstToken = UUID.randomUUID().toString();
        String secondToken = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var firstAttempt = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lease race start timed out");
                return first.tryAcquire(concurrentKey, firstToken, Duration.ofSeconds(60));
            });
            var secondAttempt = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("lease race start timed out");
                return second.tryAcquire(concurrentKey, secondToken, Duration.ofSeconds(60));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS), "Both MySQL clients must reach the lease barrier");
            start.countDown();
            boolean firstAcquired = firstAttempt.get(10, TimeUnit.SECONDS);
            boolean secondAcquired = secondAttempt.get(10, TimeUnit.SECONDS);
            assertTrue(firstAcquired ^ secondAcquired, "Exactly one independent MySQL client may own the lease");
        }
        first.release(concurrentKey, firstToken);
        second.release(concurrentKey, secondToken);

        String recoveryKey = "e".repeat(64);
        String staleToken = UUID.randomUUID().toString();
        String ownerToken = UUID.randomUUID().toString();
        assertTrue(first.tryAcquire(recoveryKey, staleToken, Duration.ofSeconds(60)));
        try (var statement = connection.prepareStatement("UPDATE knowledge_shared_answer_cache_lock "
                + "SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE cache_key=?")) {
            statement.setString(1, recoveryKey);
            assertEquals(1, statement.executeUpdate());
        }
        assertTrue(second.tryAcquire(recoveryKey, ownerToken, Duration.ofSeconds(60)));
        first.release(recoveryKey, staleToken);
        assertEquals(ownerToken, countOwnerToken(connection, recoveryKey));
        var shared = new KnowledgeAnswerCacheStore.Entry(recoveryKey, 1, "INTERVIEW_PREPARATION",
                "INTERVIEW_PREPARATION", "Shared grounded answer [2001:1]", "ALUMNI_REPORTED_EVIDENCE",
                "OPENAI_GROUNDED", "openai", "gpt-4.1-mini", "grounded-answer-v1",
                List.of(new KnowledgeAnswerCacheStore.SourceRef(2001, 1)),
                LocalDateTime.now(ZoneOffset.UTC).plusDays(1));
        assertTrue(!first.save(shared, staleToken), "An expired owner must not overwrite the new owner's entry");
        assertTrue(second.save(shared, ownerToken));
        assertEquals(shared.answer(), springStore.find(recoveryKey).orElseThrow().answer());
        assertEquals(shared.answer(), first.find(recoveryKey).orElseThrow().answer());
        first.release(recoveryKey, staleToken);
        assertEquals(ownerToken, countOwnerToken(connection, recoveryKey));
        second.release(recoveryKey, ownerToken);
    }

    private static String countOwnerToken(Connection connection, String cacheKey) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT lock_token FROM knowledge_shared_answer_cache_lock WHERE cache_key=?")) {
            statement.setString(1, cacheKey);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static void insertExpiredCacheBacklog(Connection connection, int count) throws Exception {
        String sql = """
                INSERT INTO knowledge_shared_answer_cache (
                    cache_key,cache_version,canonical_intent,canonical_question,answer_text,evidence_status,
                    answer_mode,provider_id,model_id,prompt_version,cache_status,expires_at
                ) VALUES (?,1,'INTERVIEW_PREPARATION','Interview preparation','Expired test row',
                    'ALUMNI_REPORTED_EVIDENCE','OPENAI_GROUNDED','openai','gpt-4.1-mini',
                    'grounded-answer-v1','EXPIRED',TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)))
                """;
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < count; i++) {
                statement.setString(1, String.format(java.util.Locale.ROOT, "%064x", i + 1));
                statement.addBatch();
                if ((i + 1) % 1_000 == 0) statement.executeBatch();
            }
            statement.executeBatch();
        }
    }

    private static void migrateFreshSchemaThrough21(DatabaseSettings database) {
        Flyway.configure()
                .dataSource(verifiedJdbcUrl(database), database.username(), database.password())
                .locations("classpath:db/fresh", "classpath:db/shared")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .target(MigrationVersion.fromVersion("21"))
                .load()
                .migrate();
    }

    private static void seedExistingEvidence(DatabaseSettings database) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                verifiedJdbcUrl(database), database.username(), database.password());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users
                        (id, name, email, password_hash, role, status, email_verified, account_status)
                    VALUES
                        (1001, 'Migration Test', 'phase23-migration@example.test', 'not-a-login-password',
                         'STUDENT', 'APPROVED', TRUE, 'ACTIVE')
                    """);
            statement.executeUpdate("""
                    INSERT INTO experience_claims
                        (id, user_id, company, job_title, summary, created_at, updated_at)
                    VALUES
                        (1001, 1001, 'Northstar', 'Mentor', 'Migration verification row',
                         CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """);
            statement.executeUpdate("""
                    INSERT INTO experience_claim_evidence (
                        id, claim_id, evidence_version, evidence_storage_key, encrypted_evidence,
                        evidence_file_name, evidence_media_type, evidence_size, evidence_hash, created_at
                    ) VALUES (
                        1001, 1001, 1, 'migration-key', X'01', 'migration.pdf',
                        'application/pdf', 1, 'migration-test-hash', CURRENT_TIMESTAMP
                    )
                    """);
        }
    }

    private static void seedKnowledgeCacheSource(DatabaseSettings database) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                verifiedJdbcUrl(database), database.username(), database.password());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO knowledge_contributions
                        (id, owner_user_id, status, current_version, published_version, last_confirmed_at)
                    VALUES (2001, 1001, 'PUBLISHED', 1, 1, CURRENT_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO knowledge_contribution_versions
                        (contribution_id, version_number, category, title, narrative,
                         student_visibility_consent, ai_retrieval_consent, created_by_user_id)
                    VALUES (2001, 1, 'GENERAL', 'Migration cache source', 'Disposable migration test source',
                            TRUE, TRUE, 1001)
                    """);
        }
    }

    @Test
    void knownFailedLegacyV2AfterNormalizedV1IsRepairedAndMigrated() throws Exception {
        DatabaseSettings database = settings(RECOVERY_TEST_PREFIX);
        assertEmptySchema(database);

        Flyway.configure()
                .dataSource(verifiedJdbcUrl(database), database.username(), database.password())
                .locations("classpath:db/normalized")
                .target(MigrationVersion.fromVersion("1"))
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                verifiedJdbcUrl(database), database.username(), database.password());
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
                     verifiedJdbcUrl(database), database.username(), database.password())) {
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
                || !Set.of(3306, 3307, 3308, 3309).contains(endpoint.getPort())
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
        return new IllegalArgumentException(prefix + " must target only jdbc:mysql://localhost:(3306, 3307, 3308, or 3309)/"
                + expectedDatabase + " (or an equivalent loopback address), without URL credentials or options.");
    }

    private static void assertEmptySchema(DatabaseSettings database) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                verifiedJdbcUrl(database), database.username(), database.password())) {
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

    private static String verifiedJdbcUrl(DatabaseSettings database) {
        String path = System.getenv("DB_CA_TRUSTSTORE_PATH");
        String password = System.getenv("DB_CA_TRUSTSTORE_PASSWORD");
        String type = System.getenv().getOrDefault("DB_CA_TRUSTSTORE_TYPE", "PKCS12");
        if (path == null || path.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "DB_CA_TRUSTSTORE_PATH and DB_CA_TRUSTSTORE_PASSWORD are required for MySQL integration tests");
        }
        String encodedTruststore = URLEncoder.encode(
                java.nio.file.Path.of(path).toAbsolutePath().toUri().toString(), StandardCharsets.UTF_8);
        String encodedPassword = URLEncoder.encode(password, StandardCharsets.UTF_8);
        String encodedType = URLEncoder.encode(type, StandardCharsets.UTF_8);
        return database.url() + "?sslMode=VERIFY_IDENTITY"
                + "&trustCertificateKeyStoreUrl=" + encodedTruststore
                + "&trustCertificateKeyStoreType=" + encodedType
                + "&trustCertificateKeyStorePassword=" + encodedPassword
                + "&fallbackToSystemTrustStore=false";
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
        for (String version : List.of("17", "18", "19", "20", "21", "22", "23", "24", "25")) {
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
                new String[]{"experience_claims", "claim_type"},
                new String[]{"experience_claim_evidence", "evidence_version"},
                new String[]{"experience_claim_evidence", "encrypted_evidence"},
                new String[]{"experience_claim_evidence", "evidence_status"},
                new String[]{"experience_claim_evidence", "relationship_type"},
                new String[]{"experience_claim_evidence", "related_evidence_id"},
                new String[]{"experience_claim_evidence", "superseded_at"},
                new String[]{"experience_evidence_extraction", "evidence_id"},
                new String[]{"experience_evidence_extraction", "evidence_version"},
                new String[]{"experience_evidence_extraction", "response_hash"},
                new String[]{"experience_evidence_extraction_field", "field_name"},
                new String[]{"experience_evidence_extraction_provenance", "page_number"},
                new String[]{"experience_evidence_extraction_provenance", "text_excerpt"},
                new String[]{"experience_evidence_extraction_provenance", "bounding_box_x"},
                new String[]{"experience_claim_processing", "next_attempt_at"},
                new String[]{"experience_claim_processing", "lease_token"},
                new String[]{"experience_corroboration_attempts", "independently_authenticated"},
                new String[]{"knowledge_contributions", "freshness_interval_days"},
                new String[]{"knowledge_contributions", "reminder_due_at"},
                new String[]{"knowledge_contributions", "last_reminder_sent_at"},
                new String[]{"knowledge_companion_messages", "evidence_status"},
                new String[]{"knowledge_roadmap_items", "progress_status"})) {
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
                new String[]{"experience_claim_evidence", "related_evidence_id", "experience_claim_evidence"},
                new String[]{"experience_evidence_extraction", "evidence_id", "experience_claim_evidence"},
                new String[]{"experience_evidence_extraction_provenance", "source_evidence_id",
                        "experience_evidence_extraction"},
                new String[]{"experience_claim_processing", "claim_id", "experience_claims"},
                new String[]{"experience_corroboration_attempts", "claim_id", "experience_claims"},
                new String[]{"knowledge_contributions", "owner_user_id", "users"},
                new String[]{"knowledge_contribution_versions", "contribution_id", "knowledge_contributions"},
                new String[]{"knowledge_contribution_versions", "created_by_user_id", "users"},
                new String[]{"knowledge_contribution_entries", "version_id", "knowledge_contribution_versions"},
                new String[]{"knowledge_contribution_audit", "contribution_id", "knowledge_contributions"},
                new String[]{"knowledge_contribution_audit", "contribution_id", "knowledge_contribution_versions"},
                new String[]{"knowledge_contribution_audit", "actor_user_id", "users"},
                new String[]{"knowledge_companion_conversations", "student_user_id", "users"},
                new String[]{"knowledge_companion_messages", "conversation_id", "knowledge_companion_conversations"},
                new String[]{"knowledge_companion_message_sources", "message_id", "knowledge_companion_messages"},
                new String[]{"knowledge_companion_message_sources", "contribution_id", "knowledge_contribution_versions"},
                new String[]{"knowledge_question_interest", "student_user_id", "users"},
                new String[]{"knowledge_roadmaps", "student_user_id", "users"},
                new String[]{"knowledge_roadmap_items", "roadmap_id", "knowledge_roadmaps"},
                new String[]{"knowledge_roadmap_item_sources", "item_id", "knowledge_roadmap_items"},
                new String[]{"knowledge_roadmap_item_sources", "contribution_id", "knowledge_contribution_versions"})) {
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
        assertTrue(hasUniqueIndex(connection, "experience_claim_evidence", "id", "evidence_version"));
        assertTrue(hasUniqueIndex(connection, "experience_evidence_extraction",
                "evidence_id", "evidence_version", "response_hash"));
        assertTrue(hasUniqueIndex(connection, "knowledge_contribution_versions", "contribution_id", "version_number"));
        assertTrue(hasUniqueIndex(connection, "knowledge_question_interest", "student_user_id", "topic_hash"));
        assertTrue(hasUniqueIndex(connection, "knowledge_roadmap_items", "roadmap_id", "position_index"));

        assertTrue(hasIndex(connection, "users", "idx_users_role_status_id"));
        assertTrue(hasIndex(connection, "email_outbox", "idx_email_outbox_due"));
        assertTrue(hasIndex(connection, "email_outbox", "idx_email_outbox_expiry"));
        assertTrue(hasIndex(connection, "email_outbox", "idx_email_outbox_recipient_purpose"));
        assertTrue(hasIndex(connection, "experience_claim_evidence", "idx_experience_claim_evidence_lifecycle"));
        assertTrue(hasIndex(connection, "experience_evidence_extraction", "idx_experience_extraction_history"));
        assertTrue(hasIndex(connection, "knowledge_contributions", "idx_knowledge_contributions_owner_updated"));
        assertTrue(hasIndex(connection, "knowledge_contributions", "idx_knowledge_contributions_public"));
        assertTrue(hasIndex(connection, "knowledge_contribution_entries", "idx_knowledge_entries_version_position"));
        assertTrue(hasIndex(connection, "knowledge_contributions", "idx_knowledge_freshness_due"));
        assertTrue(hasIndex(connection, "knowledge_companion_conversations", "idx_knowledge_conversation_owner_updated"));
        assertTrue(hasIndex(connection, "knowledge_question_interest", "idx_knowledge_question_topic"));
        assertTrue(hasIndex(connection, "knowledge_roadmaps", "idx_knowledge_roadmap_owner_updated"));
        assertTrue(hasIndex(connection, "knowledge_shared_answer_cache", "idx_knowledge_shared_answer_cache_expiry"));
        assertTrue(hasIndex(connection, "knowledge_shared_answer_cache", "idx_knowledge_shared_answer_cache_intent"));
        assertTrue(hasIndex(connection, "knowledge_shared_answer_cache_source", "idx_knowledge_answer_cache_source_version"));
        assertTrue(hasIndex(connection, "knowledge_shared_answer_cache_lock", "idx_knowledge_answer_cache_lock_expiry"));
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
