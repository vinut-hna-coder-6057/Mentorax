package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ExperienceClaimEvidenceRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionCandidateRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionFieldRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionProvenanceRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionStatementRepository;
import com.alumni.alumni_connect.repository.ExperienceEvidenceExtractionWarningRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class ExperienceEvidenceExtractionPersistenceServiceTest {
    @DynamicPropertySource
    static void evidenceTestKey(DynamicPropertyRegistry registry) {
        registry.add("EXPERIENCE_EVIDENCE_ENCRYPTION_KEY", () -> java.util.Base64.getEncoder()
                .encodeToString("synthetic-test-evidence-key-32!!".getBytes(StandardCharsets.UTF_8)));
        registry.add("app.experience-verification.enabled", () -> "false");
    }

    @Autowired private UserRepository userRepository;
    @Autowired private ExperienceClaimRepository claimRepository;
    @Autowired private ExperienceClaimEvidenceRepository evidenceRepository;
    @Autowired private ExperienceEvidenceExtractionRepository extractionRepository;
    @Autowired private ExperienceEvidenceExtractionFieldRepository fieldRepository;
    @Autowired private ExperienceEvidenceExtractionCandidateRepository candidateRepository;
    @Autowired private ExperienceEvidenceExtractionStatementRepository statementRepository;
    @Autowired private ExperienceEvidenceExtractionProvenanceRepository provenanceRepository;
    @Autowired private ExperienceEvidenceExtractionWarningRepository warningRepository;
    @Autowired private ExperienceEvidenceExtractionPersistenceService persistenceService;

    private ExperienceClaim claim;

    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(new User(
                "Extraction Test", "extraction-" + UUID.randomUUID() + "@example.test",
                "password", "STUDENT", "APPROVED"));
        claim = new ExperienceClaim();
        claim.setUser(user);
        claim.setCompany("Northstar");
        claim.setJobTitle("Mentor");
        claim.setSummary("Supported a learning programme");
        claim = claimRepository.saveAndFlush(claim);
    }

    @Test
    void persistsFieldsCandidatesStatementsAndExactProvenanceForEachEvidenceVersion() {
        ExperienceClaimEvidence first = evidence(1);
        ExperienceClaimEvidence second = evidence(2);
        StructuredEvidenceExtraction firstResult = extraction(first, "b".repeat(64));
        StructuredEvidenceExtraction secondResult = extraction(second, "c".repeat(64));

        Long firstRunId = persistenceService.persist(first, firstResult);
        Long replayedRunId = persistenceService.persist(first, firstResult);
        Long secondRunId = persistenceService.persist(second, secondResult);

        assertEquals(firstRunId, replayedRunId);
        assertNotEquals(firstRunId, secondRunId);
        assertEquals(2, extractionRepository.count());
        assertEquals(4, fieldRepository.count());
        assertEquals(4, candidateRepository.count());
        assertEquals(2, statementRepository.count());
        assertEquals(8, provenanceRepository.count());
        assertEquals(2, warningRepository.count());

        var firstFields = fieldRepository.findAll().stream()
                .filter(field -> field.getExtractionId().equals(firstRunId))
                .sorted(java.util.Comparator.comparing(
                        com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionField::getFieldOrdinal))
                .toList();
        var firstRun = extractionRepository.findById(firstRunId).orElseThrow();
        assertEquals(first.getId(), firstRun.getEvidenceId());
        assertEquals(first.getEvidenceVersion(), firstRun.getEvidenceVersion());
        assertEquals("mentorax.document-understanding.v1", firstRun.getSchemaVersion());
        assertEquals("a".repeat(64), firstRun.getDocumentSha256());
        assertEquals("EXPERIENCE_CERTIFICATE", firstRun.getDocumentClassification());
        assertEquals("SUCCESS", firstRun.getOcrStatus());
        assertTrue(firstRun.getProcessedAt() != null);
        assertEquals("FOUND", firstFields.get(0).getFieldStatus());
        assertEquals("Alex Example", firstFields.get(0).getExtractedValue());
        assertEquals("AMBIGUOUS", firstFields.get(1).getFieldStatus());
        assertEquals(new BigDecimal("0.91000"), firstFields.get(0).getConfidence());

        var firstProvenance = provenanceRepository.findAll().stream()
                .filter(item -> item.getExtractionId().equals(firstRunId)).toList();
        assertTrue(firstProvenance.stream().allMatch(item ->
                item.getSourceEvidenceId().equals(first.getId())
                        && item.getSourceEvidenceVersion().equals(first.getEvidenceVersion())));
        assertTrue(firstProvenance.stream().anyMatch(item ->
                item.getPageNumber() == 2 && item.getTextExcerpt().equals("Alex Example completed the programme.")
                        && "PDF_TEXT".equals(item.getExtractionMethod())
                        && new BigDecimal("0.100000").equals(item.getBoundingBoxX())));
    }

    @Test
    void rejectsMismatchedEvidenceIdentityWithoutChangingHistoricalRows() {
        ExperienceClaimEvidence evidence = evidence(1);
        StructuredEvidenceExtraction valid = extraction(evidence, "d".repeat(64));
        persistenceService.persist(evidence, valid);
        StructuredEvidenceExtraction wrongIdentity = new StructuredEvidenceExtraction(
                valid.schemaVersion(), valid.evidenceId() + 1, valid.evidenceVersion(),
                valid.documentSha256(), "e".repeat(64), valid.analysisStatus(),
                valid.documentClassification(), valid.classificationConfidence(),
                valid.classificationProvenance(), valid.ocrStatus(), valid.fields(),
                valid.statements(), valid.warnings());

        assertThrows(IllegalArgumentException.class,
                () -> persistenceService.persist(evidence, wrongIdentity));
        assertEquals(1, extractionRepository.count());
        assertEquals(2, fieldRepository.count());
    }

    @Test
    void refusesSensitiveStructuredContentBeforeCreatingExtractionRows() {
        ExperienceClaimEvidence evidence = evidence(1);
        StructuredEvidenceExtraction valid = extraction(evidence, "9".repeat(64));
        StructuredEvidenceExtraction.Field originalField = valid.fields().get(0);
        StructuredEvidenceExtraction.Field sensitiveField = new StructuredEvidenceExtraction.Field(
                originalField.ordinal(), originalField.name(), originalField.status(),
                "OTP: 735904", originalField.candidateValues(), originalField.confidence(),
                originalField.modelConfidence(), originalField.extractionMethod(),
                originalField.provenance());
        StructuredEvidenceExtraction sensitive = new StructuredEvidenceExtraction(
                valid.schemaVersion(), valid.evidenceId(), valid.evidenceVersion(),
                valid.documentSha256(), valid.responseHash(), valid.analysisStatus(),
                valid.documentClassification(), valid.classificationConfidence(),
                valid.classificationProvenance(), valid.ocrStatus(),
                List.of(sensitiveField, valid.fields().get(1)), valid.statements(), valid.warnings());

        assertThrows(IllegalArgumentException.class,
                () -> persistenceService.persist(evidence, sensitive));
        assertEquals(0, extractionRepository.count());
        assertEquals(0, fieldRepository.count());
    }

    @Test
    void storesChangedSuccessfulReprocessingAsHistoryAndDoesNotDeleteEarlierRuns() {
        ExperienceClaimEvidence evidence = evidence(1);
        StructuredEvidenceExtraction first = extraction(evidence, "f".repeat(64));
        StructuredEvidenceExtraction reprocessed = extraction(evidence, "a".repeat(64));

        Long oldRunId = persistenceService.persist(evidence, first);
        Long newRunId = persistenceService.persist(evidence, reprocessed);

        assertNotEquals(oldRunId, newRunId);
        assertEquals(2, extractionRepository.count());
        assertEquals(4, fieldRepository.count());
        assertTrue(extractionRepository.findAll().stream()
                .anyMatch(run -> run.getId().equals(oldRunId)));
        assertTrue(extractionRepository.findAll().stream()
                .anyMatch(run -> run.getId().equals(newRunId)));
    }

    @Test
    void latestLookupReconstructsOnlyTheExactEvidenceVersionAndPreservesEarlierRuns() {
        ExperienceClaimEvidence firstVersion = evidence(1);
        ExperienceClaimEvidence secondVersion = evidence(2);
        StructuredEvidenceExtraction firstRun = extraction(firstVersion, "1".repeat(64));
        StructuredEvidenceExtraction newerRun = extraction(firstVersion, "2".repeat(64));
        StructuredEvidenceExtraction secondVersionRun = extraction(secondVersion, "3".repeat(64));

        Long firstRunId = persistenceService.persist(firstVersion, firstRun);
        persistenceService.persist(firstVersion, newerRun);
        persistenceService.persist(secondVersion, secondVersionRun);

        StructuredEvidenceExtraction latestFirstVersion =
                persistenceService.findLatest(firstVersion.getId(), firstVersion.getEvidenceVersion())
                        .orElseThrow();
        StructuredEvidenceExtraction latestSecondVersion =
                persistenceService.findLatest(secondVersion.getId(), secondVersion.getEvidenceVersion())
                        .orElseThrow();

        assertEquals(firstVersion.getId(), latestFirstVersion.evidenceId());
        assertEquals(firstVersion.getEvidenceVersion(), latestFirstVersion.evidenceVersion());
        assertEquals(newerRun.responseHash(), latestFirstVersion.responseHash());
        assertEquals("Alex Example", latestFirstVersion.fields().get(0).value());
        assertEquals(firstVersion.getId(), latestFirstVersion.fields().get(0)
                .provenance().get(0).sourceEvidenceId());
        assertEquals(firstVersion.getEvidenceVersion(), latestFirstVersion.fields().get(0)
                .provenance().get(0).evidenceVersion());
        assertEquals(secondVersion.getId(), latestSecondVersion.evidenceId());
        assertEquals(secondVersion.getEvidenceVersion(), latestSecondVersion.evidenceVersion());
        assertEquals(secondVersionRun.responseHash(), latestSecondVersion.responseHash());
        assertTrue(extractionRepository.findById(firstRunId).isPresent());
        assertTrue(persistenceService.findLatest(firstVersion.getId(), 3).isEmpty());
    }

    private ExperienceClaimEvidence evidence(int version) {
        return evidenceRepository.saveAndFlush(new ExperienceClaimEvidence(
                claim.getId(), version, "storage-key-" + version, new byte[]{1, 2, 3},
                "evidence-" + version + ".pdf", "application/pdf", 3L,
                "document-" + version));
    }

    private StructuredEvidenceExtraction extraction(ExperienceClaimEvidence evidence, String responseHash) {
        StructuredEvidenceExtraction.Provenance provenance = new StructuredEvidenceExtraction.Provenance(
                evidence.getId(),
                evidence.getEvidenceVersion(),
                2,
                "Alex Example completed the programme.",
                "PDF_TEXT",
                new BigDecimal("0.88000"),
                new StructuredEvidenceExtraction.BoundingBox(
                        new BigDecimal("0.100000"), new BigDecimal("0.200000"),
                        new BigDecimal("0.300000"), new BigDecimal("0.100000")));
        List<StructuredEvidenceExtraction.Field> fields = List.of(
                new StructuredEvidenceExtraction.Field(
                        0, "personName", "FOUND", "Alex Example", List.of(),
                        new BigDecimal("0.91000"), new BigDecimal("0.87000"),
                        "LABEL_RULE", List.of(provenance)),
                new StructuredEvidenceExtraction.Field(
                        1, "organisation", "AMBIGUOUS", null, List.of("Northstar", "Northstar Labs"),
                        null, null, "LABEL_RULE", List.of(provenance)));
        List<StructuredEvidenceExtraction.Statement> statements = List.of(
                new StructuredEvidenceExtraction.Statement(
                        0, "COMPLETION", "Alex Example completed the programme.", List.of(provenance)));
        return new StructuredEvidenceExtraction(
                "mentorax.document-understanding.v1",
                evidence.getId(),
                evidence.getEvidenceVersion(),
                "a".repeat(64),
                responseHash,
                "COMPLETE",
                "EXPERIENCE_CERTIFICATE",
                new BigDecimal("0.92000"),
                List.of(provenance),
                "SUCCESS",
                fields,
                statements,
                List.of("Issuer was not independently verified"));
    }
}
