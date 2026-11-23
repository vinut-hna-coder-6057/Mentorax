package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import com.alumni.alumni_connect.entity.ExperienceClaimProcessing;
import com.alumni.alumni_connect.entity.ExperienceClaimType;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ExperienceClaimEvidenceRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimProcessingRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EnabledIfSystemProperty(named = "phase26.processing.mysql", matches = "true")
class ExperienceClaimProcessingMySqlLifecycleTest {
    private static final String DATABASE_NAME = "mentorax_phase26_lifecycle_test";
    private static final String API_TOKEN = "synthetic-phase26-test-token";
    private static final String ENCRYPTION_KEY = java.util.Base64.getEncoder()
            .encodeToString("synthetic-test-evidence-key-32!!".getBytes(StandardCharsets.UTF_8));
    private static ControlledAnalysisServer analysisServer;
    private static DatabaseSettings database;

    @Autowired private UserRepository userRepository;
    @Autowired private ExperienceClaimRepository claimRepository;
    @Autowired private ExperienceClaimEvidenceRepository evidenceRepository;
    @Autowired private ExperienceClaimProcessingRepository processingRepository;
    @Autowired private ExperienceEvidenceExtractionRepository extractionRepository;
    @Autowired private ExperienceClaimProcessingService processingService;
    @Autowired private ExperienceEvidenceCrypto evidenceCrypto;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetAnalysisServer() {
        analysisServer.reset();
    }

    @DynamicPropertySource
    static void configureIsolatedMySql(DynamicPropertyRegistry registry) {
        if (analysisServer == null) analysisServer = startAnalysisServer();
        database = DatabaseSettings.fromEnvironment();
        assertEmptySchema(database);
        registry.add("spring.datasource.url", () -> database.url());
        registry.add("spring.datasource.username", () -> database.username());
        registry.add("spring.datasource.password", () -> database.password());
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("jwt.secret", () -> "synthetic-phase26-mysql-test-jwt-secret");
        registry.add("app.cors.allowed-origin-patterns", () -> "http://localhost");
        registry.add("app.websocket.allowed-origins", () -> "http://localhost");
        registry.add("app.email-outbox.enabled", () -> "false");
        registry.add("app.experience-verification.enabled", () -> "false");
        registry.add("app.ai.service-url", analysisServer::baseUrl);
        registry.add("EXPERIENCE_ANALYSIS_API_TOKEN", () -> API_TOKEN);
        registry.add("EXPERIENCE_EVIDENCE_ENCRYPTION_KEY", () -> ENCRYPTION_KEY);
    }

    @AfterAll
    static void stopAnalysisServer() {
        if (analysisServer != null) analysisServer.close();
    }

    @Test
    void retriesRecoverablePythonFailureAndKeepsSuccessfulExtractionIdempotent() throws Exception {
        SeededEvidence seeded = createClaimAndEvidence("retry");
        ExperienceClaimProcessingScheduler scheduler =
                new ExperienceClaimProcessingScheduler(processingRepository, processingService, 30);
        analysisServer.enqueue(503, "{\"error\":\"temporary-unavailable\"}");

        LocalDateTime beforeFailure = LocalDateTime.now();
        scheduler.processDueClaims();
        LocalDateTime afterFailure = LocalDateTime.now();

        ExperienceClaimProcessing failedJob =
                processingRepository.findById(seeded.claimId()).orElseThrow();
        assertEquals(1, failedJob.getAttemptCount());
        assertNull(failedJob.getLeaseUntil());
        assertEquals("The analysis service is temporarily unavailable.", failedJob.getLastError());
        assertTrue(failedJob.getNextAttemptAt().isAfter(beforeFailure.plusSeconds(4)));
        assertTrue(failedJob.getNextAttemptAt().isBefore(afterFailure.plusSeconds(6)));
        assertEquals("PROCESSING", claimRepository.findById(seeded.claimId()).orElseThrow().getStatus());
        assertEquals(0, extractionCount(seeded.evidenceId(), 1));
        assertEquals(1, analysisServer.requestCount());
        assertTrue(analysisServer.allRequestsAuthenticated());

        jdbcTemplate.update(
                "UPDATE experience_claim_processing SET next_attempt_at = ? WHERE claim_id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), seeded.claimId());
        analysisServer.enqueue(200, strictResponse(seeded));
        scheduler.processDueClaims();

        ExperienceClaim completedClaim = claimRepository.findById(seeded.claimId()).orElseThrow();
        assertEquals("ANALYZED", completedClaim.getAiStatus());
        assertFalse("PROCESSING".equals(completedClaim.getStatus()));
        assertFalse(processingRepository.existsById(seeded.claimId()));
        assertEquals(2, analysisServer.requestCount());
        assertTrue(analysisServer.allRequestsAuthenticated());
        assertPersistedExtraction(seeded);
        String responseHash = latestResponseHash(seeded.evidenceId(), 1);

        processingRepository.saveAndFlush(new ExperienceClaimProcessing(
                seeded.claimId(), 1, LocalDateTime.now().minusSeconds(1)));
        analysisServer.enqueue(200, strictResponse(seeded));
        scheduler.processDueClaims();

        assertEquals(3, analysisServer.requestCount());
        assertEquals(1, extractionCount(seeded.evidenceId(), 1));
        assertEquals(responseHash, latestResponseHash(seeded.evidenceId(), 1));
        assertEquals(completedClaim.getStatus(), claimRepository.findById(seeded.claimId()).orElseThrow().getStatus());
        assertPersistedExtraction(seeded);
    }

    @Test
    void recoversExpiredLeaseAndRejectsAnOldWorkersProcessingAndFailureWrites() throws Exception {
        SeededEvidence seeded = createClaimAndEvidence("stale");
        LocalDateTime now = LocalDateTime.now();
        ExperienceClaimProcessingService.ProcessingLease firstLease = processingService.acquireLease(
                seeded.claimId(), now, now.plusSeconds(60)).orElseThrow();

        assertEquals(1, firstLease.attemptCount());
        assertNotNull(firstLease.token());
        assertEquals(firstLease.token(),
                processingRepository.findById(seeded.claimId()).orElseThrow().getLeaseToken());
        assertTrue(processingService.acquireLease(
                seeded.claimId(), now, now.plusSeconds(60)).isEmpty(),
                "A live lease must not be acquired by another worker");
        assertEquals(1, processingRepository.findById(seeded.claimId()).orElseThrow().getAttemptCount());

        LocalDateTime expiredAt = LocalDateTime.now().minusSeconds(2);
        jdbcTemplate.update(
                "UPDATE experience_claim_processing SET lease_until = ?, next_attempt_at = ? WHERE claim_id = ?",
                Timestamp.valueOf(expiredAt), Timestamp.valueOf(expiredAt), seeded.claimId());
        ExperienceClaimProcessingService.ProcessingLease recoveredLease = processingService.acquireLease(
                seeded.claimId(), LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)).orElseThrow();
        assertEquals(2, recoveredLease.attemptCount());
        assertFalse(firstLease.token().equals(recoveredLease.token()));

        analysisServer.enqueue(200, strictResponse(seeded));
        processingService.processClaim(firstLease);
        processingService.recordUnexpectedFailure(firstLease);

        ExperienceClaimProcessing recoveredJob =
                processingRepository.findById(seeded.claimId()).orElseThrow();
        assertEquals(2, recoveredJob.getAttemptCount());
        assertNull(recoveredJob.getLastError());
        assertEquals("PENDING", claimRepository.findById(seeded.claimId()).orElseThrow().getStatus());
        assertEquals(0, analysisServer.requestCount());
        assertEquals(0, extractionCount(seeded.evidenceId(), 1));

        processingService.processClaim(recoveredLease);

        assertEquals(1, analysisServer.requestCount());
        assertTrue(analysisServer.allRequestsAuthenticated());
        assertFalse(processingRepository.existsById(seeded.claimId()));
        assertEquals("ANALYZED", claimRepository.findById(seeded.claimId()).orElseThrow().getAiStatus());
        assertPersistedExtraction(seeded);
    }

    @Test
    void leaseTokenRejectsOldProcessingAndFailureWritesWhenVersionAndAttemptAreReused() throws Exception {
        SeededEvidence seeded = createClaimAndEvidence("token-collision");
        LocalDateTime firstNow = LocalDateTime.now();
        ExperienceClaimProcessingService.ProcessingLease firstLease = processingService.acquireLease(
                seeded.claimId(), firstNow, firstNow.plusSeconds(60)).orElseThrow();

        LocalDateTime requeuedAt = LocalDateTime.now().minusSeconds(2);
        jdbcTemplate.update(
                "UPDATE experience_claim_processing SET attempt_count = 0, lease_until = ?, "
                        + "lease_token = NULL, next_attempt_at = ? WHERE claim_id = ?",
                Timestamp.valueOf(requeuedAt), Timestamp.valueOf(requeuedAt), seeded.claimId());
        LocalDateTime secondNow = LocalDateTime.now();
        ExperienceClaimProcessingService.ProcessingLease secondLease = processingService.acquireLease(
                seeded.claimId(), secondNow, secondNow.plusSeconds(60)).orElseThrow();

        assertEquals(firstLease.evidenceVersion(), secondLease.evidenceVersion());
        assertEquals(firstLease.attemptCount(), secondLease.attemptCount());
        assertFalse(firstLease.token().equals(secondLease.token()));
        assertEquals(secondLease.token(),
                processingRepository.findById(seeded.claimId()).orElseThrow().getLeaseToken());

        processingService.processClaim(firstLease);
        processingService.recordUnexpectedFailure(firstLease);

        ExperienceClaimProcessing currentJob =
                processingRepository.findById(seeded.claimId()).orElseThrow();
        assertEquals(secondLease.token(), currentJob.getLeaseToken());
        assertEquals(secondLease.attemptCount(), currentJob.getAttemptCount());
        assertNull(currentJob.getLastError());
        assertEquals("PENDING", claimRepository.findById(seeded.claimId()).orElseThrow().getStatus());
        assertEquals(0, analysisServer.requestCount());
        assertEquals(0, extractionCount(seeded.evidenceId(), seeded.evidenceVersion()));

        analysisServer.enqueue(200, strictResponse(seeded));
        processingService.processClaim(secondLease);

        assertEquals(1, analysisServer.requestCount());
        assertFalse(processingRepository.existsById(seeded.claimId()));
        assertEquals("ANALYZED", claimRepository.findById(seeded.claimId()).orElseThrow().getAiStatus());
        assertPersistedExtraction(seeded);
    }

    @Test
    void newerEvidenceLeaseCannotBeReusedByAWorkerFromThePreviousVersion() throws Exception {
        SeededEvidence firstVersion = createClaimAndEvidence("version-race");
        ExperienceClaimProcessingService.ProcessingLease workerA = processingService.acquireLease(
                firstVersion.claimId(), LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)).orElseThrow();

        ExperienceClaim claim = claimRepository.findById(firstVersion.claimId()).orElseThrow();
        ExperienceClaimEvidence firstEvidence = evidenceRepository.findById(firstVersion.evidenceId()).orElseThrow();
        firstEvidence.setEvidenceStatus("SUPERSEDED");
        firstEvidence.setSupersededAt(LocalDateTime.now());
        evidenceRepository.saveAndFlush(firstEvidence);
        byte[] secondDocument = syntheticPdf("Synthetic corrected certificate");
        String secondHash = sha256(secondDocument);
        ExperienceClaimEvidence secondEvidence = evidenceRepository.saveAndFlush(new ExperienceClaimEvidence(
                claim.getId(),
                2,
                "phase26-version-race-" + UUID.randomUUID(),
                evidenceCrypto.encrypt(secondDocument),
                "synthetic-corrected-certificate.pdf",
                "application/pdf",
                (long) secondDocument.length,
                secondHash));
        secondEvidence.setRelationshipType("CORRECTION");
        secondEvidence.setRelatedEvidenceId(firstEvidence.getId());
        evidenceRepository.saveAndFlush(secondEvidence);
        claim.setEvidenceVersion(2);
        claim.setEvidenceStorageKey(secondEvidence.getEvidenceStorageKey());
        claim.setEncryptedEvidence(secondEvidence.getEncryptedEvidence());
        claim.setEvidenceFileName(secondEvidence.getEvidenceFileName());
        claim.setEvidenceMediaType(secondEvidence.getEvidenceMediaType());
        claim.setEvidenceSize(secondEvidence.getEvidenceSize());
        claim.setEvidenceHash(secondEvidence.getEvidenceHash());
        claim.setStatus("PROCESSING");
        claimRepository.saveAndFlush(claim);

        ExperienceClaimProcessing queued = processingRepository.findById(firstVersion.claimId()).orElseThrow();
        queued.setEvidenceVersion(2);
        queued.setAttemptCount(0);
        queued.setLeaseUntil(null);
        queued.setLeaseToken(null);
        queued.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        processingRepository.saveAndFlush(queued);

        ExperienceClaimProcessingService.ProcessingLease workerB = processingService.acquireLease(
                firstVersion.claimId(), LocalDateTime.now(), LocalDateTime.now().plusSeconds(60)).orElseThrow();
        SeededEvidence secondVersion = new SeededEvidence(
                firstVersion.claimId(), secondEvidence.getId(), secondHash, 2);

        assertEquals(1, workerA.attemptCount());
        assertEquals(1, workerB.attemptCount(),
                "The newer evidence upload resets its retry count without reusing the old lease token");
        assertEquals(1, workerA.evidenceVersion());
        assertEquals(2, workerB.evidenceVersion());
        assertFalse(workerA.token().equals(workerB.token()));

        processingService.processClaim(workerA);
        processingService.recordUnexpectedFailure(workerA);
        assertEquals(0, analysisServer.requestCount());
        assertEquals(0, extractionCount(firstVersion.evidenceId(), 1));
        assertEquals(0, extractionCount(secondVersion.evidenceId(), 2));
        assertEquals(workerB.token(),
                processingRepository.findById(firstVersion.claimId()).orElseThrow().getLeaseToken());

        analysisServer.enqueue(200, strictResponse(secondVersion));
        processingService.processClaim(workerB);

        assertEquals(1, analysisServer.requestCount());
        assertEquals(0, extractionCount(firstVersion.evidenceId(), 1));
        assertEquals(1, extractionCount(secondVersion.evidenceId(), 2));
        assertEquals("ANALYZED", claimRepository.findById(firstVersion.claimId()).orElseThrow().getAiStatus());
        assertPersistedExtraction(secondVersion);
        processingService.processClaim(workerA);
        assertEquals("ANALYZED", claimRepository.findById(firstVersion.claimId()).orElseThrow().getAiStatus());
        assertEquals(1, extractionCount(secondVersion.evidenceId(), 2));
    }

    @Test
    void exhaustsRetriesWithoutWritingSuccessfulExtraction() throws Exception {
        SeededEvidence seeded = createClaimAndEvidence("exhausted");
        ExperienceClaimProcessing job = processingRepository.findById(seeded.claimId()).orElseThrow();
        job.setAttemptCount(4);
        processingRepository.saveAndFlush(job);
        analysisServer.enqueue(503, "{\"error\":\"temporary-unavailable\"}");

        new ExperienceClaimProcessingScheduler(processingRepository, processingService, 30)
                .processDueClaims();

        ExperienceClaim failedClaim = claimRepository.findById(seeded.claimId()).orElseThrow();
        assertEquals("PROCESSING_FAILED", failedClaim.getStatus());
        assertEquals("UNAVAILABLE", failedClaim.getAiStatus());
        assertFalse(processingRepository.existsById(seeded.claimId()));
        assertEquals(0, extractionCount(seeded.evidenceId(), 1));
        assertEquals(1, analysisServer.requestCount());
        assertTrue(analysisServer.allRequestsAuthenticated());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_claim_audit "
                        + "WHERE claim_id = ? AND event_type = 'PROCESSING_FAILED'",
                Integer.class, seeded.claimId()));
    }

    private SeededEvidence createClaimAndEvidence(String purpose) throws Exception {
        User user = new User(
                "Synthetic Lifecycle User",
                "phase26-" + purpose + "-" + UUID.randomUUID() + "@example.test",
                "synthetic-not-a-login-password",
                "STUDENT",
                "APPROVED");
        user.setEmailVerified(true);
        user = userRepository.saveAndFlush(user);

        ExperienceClaim claim = new ExperienceClaim();
        claim.setUser(user);
        claim.setCompany("Synthetic Northstar");
        claim.setJobTitle("Research Intern");
        claim.setClaimType(ExperienceClaimType.INTERNSHIP);
        claim.setSummary("Completed a synthetic internship for lifecycle verification.");
        claim.setStatus("PENDING");
        claim.setAiStatus("QUEUED");
        claim.setEvidenceVersion(1);
        claim = claimRepository.saveAndFlush(claim);

        byte[] document = syntheticPdf();
        String documentHash = sha256(document);
        ExperienceClaimEvidence evidence = evidenceRepository.saveAndFlush(new ExperienceClaimEvidence(
                claim.getId(),
                1,
                "phase26-" + UUID.randomUUID(),
                evidenceCrypto.encrypt(document),
                "synthetic-certificate.pdf",
                "application/pdf",
                (long) document.length,
                documentHash));
        processingRepository.saveAndFlush(new ExperienceClaimProcessing(
                claim.getId(), 1, LocalDateTime.now().minusSeconds(1)));
        return new SeededEvidence(claim.getId(), evidence.getId(), documentHash, 1);
    }

    private void assertPersistedExtraction(SeededEvidence seeded) {
        assertEquals(1, extractionCount(seeded.evidenceId(), seeded.evidenceVersion()));
        Long extractionId = jdbcTemplate.queryForObject(
                "SELECT id FROM experience_evidence_extraction "
                        + "WHERE evidence_id = ? AND evidence_version = ? "
                        + "ORDER BY processed_at DESC, id DESC LIMIT 1",
                Long.class, seeded.evidenceId(), seeded.evidenceVersion());
        assertNotNull(extractionId);
        assertEquals("COMPLETE", jdbcTemplate.queryForObject(
                "SELECT analysis_status FROM experience_evidence_extraction WHERE id = ?",
                String.class, extractionId));
        assertEquals(seeded.documentHash(), jdbcTemplate.queryForObject(
                "SELECT document_sha256 FROM experience_evidence_extraction WHERE id = ?",
                String.class, extractionId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction_field "
                        + "WHERE extraction_id = ? AND field_name = 'personName' "
                        + "AND field_status = 'FOUND' AND extracted_value = 'Alex Example'",
                Integer.class, extractionId));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction_candidate c "
                        + "JOIN experience_evidence_extraction_field f ON f.id = c.field_id "
                        + "WHERE f.extraction_id = ? AND f.field_name = 'organisation'",
                Integer.class, extractionId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction_field "
                        + "WHERE extraction_id = ? AND field_name = 'organisation' "
                        + "AND field_status = 'AMBIGUOUS' AND extracted_value IS NULL",
                Integer.class, extractionId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction_statement "
                        + "WHERE extraction_id = ? AND statement_kind = 'COMPLETION'",
                Integer.class, extractionId));
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction_provenance "
                        + "WHERE extraction_id = ? AND source_evidence_id = ? AND source_evidence_version = ?",
                Integer.class, extractionId, seeded.evidenceId(), seeded.evidenceVersion()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction_warning WHERE extraction_id = ?",
                Integer.class, extractionId));
        assertTrue(latestResponseHash(seeded.evidenceId(), seeded.evidenceVersion()).matches("[a-f0-9]{64}"));
    }

    private int extractionCount(long evidenceId, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experience_evidence_extraction "
                        + "WHERE evidence_id = ? AND evidence_version = ?",
                Integer.class, evidenceId, version);
    }

    private String latestResponseHash(long evidenceId, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT response_hash FROM experience_evidence_extraction "
                        + "WHERE evidence_id = ? AND evidence_version = ? "
                        + "ORDER BY processed_at DESC, id DESC LIMIT 1",
                String.class, evidenceId, version);
    }

    private static byte[] syntheticPdf() throws IOException {
        return syntheticPdf("Synthetic lifecycle certificate");
    }

    private static byte[] syntheticPdf(String title) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.getDocumentInformation().setTitle(title);
            document.save(output);
            return output.toByteArray();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String strictResponse(SeededEvidence seeded) {
        String provenance = """
                {"sourceEvidenceId":%d,"evidenceVersion":%d,"pageNumber":1,
                 "textExcerpt":"Synthetic certificate for Alex Example",
                 "extractionMethod":"PDF_TEXT","sourceConfidence":0.88}
                """.formatted(seeded.evidenceId(), seeded.evidenceVersion());
        String organisationProvenance = """
                {"sourceEvidenceId":%d,"evidenceVersion":%d,"pageNumber":1,
                 "textExcerpt":"Organization: Synthetic Northstar",
                 "extractionMethod":"PDF_TEXT","sourceConfidence":0.86}
                """.formatted(seeded.evidenceId(), seeded.evidenceVersion());
        return """
                {
                  "schemaVersion":"mentorax.document-understanding.v1",
                  "analysisStatus":"COMPLETE",
                  "evidenceId":%d,
                  "evidenceVersion":%d,
                  "sha256":"%s",
                  "documentClassification":"EXPERIENCE_CERTIFICATE",
                  "classificationConfidence":0.92,
                  "classificationProvenance":[%s],
                  "ocrStatus":"NOT_NEEDED",
                  "trustedIssuerValidation":false,
                  "fields":[{
                    "name":"personName","status":"FOUND","value":"Alex Example",
                    "candidateValues":[],"confidence":0.91,"modelConfidence":0.87,
                    "extractionMethod":"LABEL_RULE","provenance":[%s]
                  },{
                    "name":"organisation","status":"AMBIGUOUS","value":null,
                    "candidateValues":["Synthetic Northstar","Northstar Labs"],
                    "confidence":0.75,"modelConfidence":0.72,
                    "extractionMethod":"LABEL_RULE","provenance":[%s]
                  }],
                  "extractedStatements":[{
                    "kind":"COMPLETION","text":"Alex Example completed the internship.",
                    "provenance":[%s]
                  }],
                  "warnings":["Issuer was not independently verified"]
                }
                """.formatted(
                seeded.evidenceId(), seeded.evidenceVersion(), seeded.documentHash(), provenance, provenance,
                        organisationProvenance, provenance);
    }

    private static void assertEmptySchema(DatabaseSettings settings) {
        try (Connection connection = DriverManager.getConnection(
                verifiedJdbcUrl(settings), settings.username(), settings.password())) {
            assertEquals(settings.schema(), connection.getCatalog());
            try (var tables = connection.getMetaData().getTables(
                    connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                assertFalse(tables.next(),
                        "Refusing to run the lifecycle integration test against a populated MySQL schema");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not validate the fresh disposable lifecycle MySQL schema", exception);
        }
    }

    private static String verifiedJdbcUrl(DatabaseSettings settings) {
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
        return settings.url() + "?sslMode=VERIFY_IDENTITY"
                + "&trustCertificateKeyStoreUrl=" + encodedTruststore
                + "&trustCertificateKeyStoreType=" + encodedType
                + "&trustCertificateKeyStorePassword=" + encodedPassword
                + "&fallbackToSystemTrustStore=false";
    }

    private record SeededEvidence(long claimId, long evidenceId, String documentHash, int evidenceVersion) {}

    private record DatabaseSettings(String url, String username, String password, String schema) {
        private static DatabaseSettings fromEnvironment() {
            String url = setting("PHASE26_PROCESSING_MYSQL_URL");
            String username = setting("PHASE26_PROCESSING_MYSQL_USERNAME");
            String password = setting("PHASE26_PROCESSING_MYSQL_PASSWORD");
            String schema = DATABASE_NAME;
            if (url == null || username == null || password == null) {
                throw new IllegalStateException(
                        "Explicit PHASE26_PROCESSING_MYSQL_URL, _USERNAME, and _PASSWORD are required.");
            }
            URI endpoint = URI.create(url.substring(url.indexOf(':') + 1));
            boolean loopback = "localhost".equalsIgnoreCase(endpoint.getHost())
                    || "127.0.0.1".equals(endpoint.getHost())
                    || "::1".equals(endpoint.getHost())
                    || "[::1]".equals(endpoint.getHost());
            if (!url.startsWith("jdbc:mysql://")
                    || !"mysql".equalsIgnoreCase(endpoint.getScheme())
                    || !loopback
                    || endpoint.getPort() != 3308
                    || !("/" + schema).equals(endpoint.getPath())
                    || endpoint.getUserInfo() != null
                    || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null) {
                throw new IllegalArgumentException(
                        "Lifecycle MySQL test is restricted to the dedicated loopback schema on port 3308.");
            }
            return new DatabaseSettings(url, username, password, schema);
        }

        private static String setting(String name) {
            String property = System.getProperty("phase26.processing.mysql." + name
                    .substring("PHASE26_PROCESSING_MYSQL_".length()).toLowerCase(Locale.ROOT));
            return property != null ? property : System.getenv(name);
        }
    }

    private static ControlledAnalysisServer startAnalysisServer() {
        try {
            return new ControlledAnalysisServer();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class ControlledAnalysisServer {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final LinkedBlockingQueue<Response> responses = new LinkedBlockingQueue<>();
        private final AtomicInteger requestCount = new AtomicInteger();
        private final AtomicBoolean authenticated = new AtomicBoolean(true);

        private ControlledAnalysisServer() throws IOException {
            server = HttpServer.create(
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            server.createContext("/experience/analyze", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void enqueue(int status, String body) {
            responses.add(new Response(status, body));
        }

        private int requestCount() {
            return requestCount.get();
        }

        private boolean allRequestsAuthenticated() {
            return authenticated.get();
        }

        private void reset() {
            responses.clear();
            requestCount.set(0);
            authenticated.set(true);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                requestCount.incrementAndGet();
                if (!"POST".equals(exchange.getRequestMethod())
                        || !API_TOKEN.equals(exchange.getRequestHeaders().getFirst("Authorization")
                        .replaceFirst("^Bearer ", ""))) {
                    authenticated.set(false);
                }
                exchange.getRequestBody().readAllBytes();
                Response response = responses.poll();
                if (response == null) response = new Response(500, "{\"error\":\"unexpected-request\"}");
                byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            } finally {
                exchange.close();
            }
        }

        private void close() {
            server.stop(0);
            executor.shutdownNow();
        }

        private record Response(int status, String body) {}
    }
}
