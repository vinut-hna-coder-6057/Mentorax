package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimType;
import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import com.alumni.alumni_connect.entity.ExperienceClaimProcessing;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ExperienceClaimAuditRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimEvidenceRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimProcessingRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperienceClaimProcessingServiceTest {
    private static final long CLAIM_ID = 42L;

    @Mock private ExperienceClaimRepository claimRepository;
    @Mock private ExperienceClaimEvidenceRepository evidenceRepository;
    @Mock private ExperienceClaimProcessingRepository processingRepository;
    @Mock private ExperienceClaimAuditRepository auditRepository;
    @Mock private ExperienceEvidenceCrypto evidenceCrypto;
    @Mock private ExperienceEvidenceAnalysisService analysisService;
    @Mock private ExperienceEvidenceExtractionPersistenceService extractionPersistenceService;
    @Mock private EmploymentCorroborationService corroborationService;
    @Mock private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExperienceClaimProcessingService processingService;
    private ExperienceClaim claim;
    private ExperienceClaimProcessing job;
    private final Map<EvidenceIdentity, StructuredEvidenceExtraction> persistedExtractions = new HashMap<>();

    @BeforeEach
    void setUp() {
        processingService = new ExperienceClaimProcessingService(
                claimRepository,
                evidenceRepository,
                processingRepository,
                auditRepository,
                evidenceCrypto,
                analysisService,
                extractionPersistenceService,
                new ExperienceVerificationDecisionEngine(),
                corroborationService,
                notificationService,
                objectMapper,
                3);
        claim = new ExperienceClaim();
        claim.setCompany("Northstar");
        claim.setJobTitle("Engineer");
        claim.setSummary("Built reliable systems");
        claim.setUser(new User("Example User", "example@example.test", "password", "STUDENT", "APPROVED"));
        claim.setEvidenceVersion(1);
        claim.setStatus("PROCESSING");
        when(claimRepository.findByIdForUpdate(CLAIM_ID)).thenReturn(Optional.of(claim));
        job = new ExperienceClaimProcessing(CLAIM_ID, 1, LocalDateTime.now());
        job.setAttemptCount(1);
        job.setLeaseUntil(LocalDateTime.now().plusMinutes(1));
        when(processingRepository.findByIdForUpdate(CLAIM_ID)).thenReturn(Optional.of(job));
        lenient().when(evidenceCrypto.decrypt(any(byte[].class))).thenReturn(new byte[]{1, 2, 3});
        lenient().when(corroborationService.verify(any()))
                .thenReturn(new EmploymentCorroborationService.Result("NOT_CHECKED", false));
        lenient().doAnswer(invocation -> {
            ExperienceClaimEvidence evidence = invocation.getArgument(0);
            StructuredEvidenceExtraction extraction = invocation.getArgument(1);
            if (evidence.getId() != null
                    && extraction.evidenceId() == evidence.getId()
                    && extraction.evidenceVersion() == evidence.getEvidenceVersion()) {
                persistedExtractions.put(
                        new EvidenceIdentity(evidence.getId(), evidence.getEvidenceVersion()), extraction);
            }
            return 1L;
        }).when(extractionPersistenceService).persist(any(), any());
        lenient().when(extractionPersistenceService.findLatest(any(), any())).thenAnswer(invocation ->
                Optional.ofNullable(persistedExtractions.get(new EvidenceIdentity(
                        invocation.getArgument(0), invocation.getArgument(1)))));
    }

    @Test
    void persistsEvidenceBasedDecisionAndNotifiesWithoutEmployerConfirmation() throws Exception {
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID)).thenReturn(List.of(
                evidence(1, "certificate.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString())).thenReturn(
                analysis("Northstar", "EMPLOYMENT_CERTIFICATE", null, 1));
        when(corroborationService.verify(claim))
                .thenReturn(new EmploymentCorroborationService.Result("NOT_CHECKED", false));

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("VERIFIED_FROM_EVIDENCE", claim.getStatus());
        assertEquals("LLM_ANALYSIS", claim.getAnalysisMethod());
        assertEquals("NOT_NEEDED", claim.getOcrStatus());
        assertEquals("NOT_CHECKED", claim.getCorroborationStatus());
        assertTrue(claim.getDecisionExplanation().contains("not independently confirmed"));
        JsonNode persistedFindings = objectMapper.readTree(claim.getAiFindings());
        assertEquals(1, persistedFindings.get(0).path("evidenceVersion").asInt());
        verify(processingRepository).deleteByClaimIdAndEvidenceVersion(CLAIM_ID, 1);
        verify(notificationService).sendNotification(
                "example@example.test",
                "Experience claim analysis is complete: VERIFIED FROM EVIDENCE",
                "EXPERIENCE_CLAIM_UPDATED",
                "/profile");
    }

    @Test
    void crossDocumentContradictionIsPersistedWithBothEvidenceReferences() throws Exception {
        claim.setEvidenceVersion(2);
        job.setEvidenceVersion(2);
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID)).thenReturn(List.of(
                evidence(1, "certificate.pdf"),
                evidence(2, "payslip.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(analysis("Northstar", "EMPLOYMENT_CERTIFICATE", null, 1))
                .thenReturn(analysis("Northstar Inc", "PAYSLIP", null, 2));

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("INCONSISTENT", claim.getStatus(), claim.getAiFindings());
        JsonNode persistedFindings = objectMapper.readTree(claim.getAiFindings());
        JsonNode contradiction = null;
        for (JsonNode finding : persistedFindings) {
            if (finding.path("field").asText().equals("company")
                    && finding.has("evidenceVersions")) {
                contradiction = finding;
                break;
            }
        }
        assertTrue(contradiction != null);
        assertEquals("MISMATCH", contradiction.path("outcome").asText());
        assertEquals(2, contradiction.path("evidenceVersions").size());
        assertEquals("document-version-1", contradiction.path("evidenceReferences").get(0).asText());
        assertEquals("document-version-2", contradiction.path("evidenceReferences").get(1).asText());
    }

    @Test
    void onlyActiveEvidenceContributesToCurrentDecisionAndCrossDocumentConflicts() {
        claim.setEvidenceVersion(3);
        job.setEvidenceVersion(3);
        ExperienceClaimEvidence superseded = evidence(1, "superseded.pdf");
        superseded.setEvidenceStatus("SUPERSEDED");
        ExperienceClaimEvidence withdrawn = evidence(2, "withdrawn.pdf");
        withdrawn.setEvidenceStatus("WITHDRAWN");
        persistedExtractions.put(new EvidenceIdentity(100L, 1),
                extraction(100L, 1, "Old Organization", "EMPLOYMENT_CERTIFICATE"));
        persistedExtractions.put(new EvidenceIdentity(200L, 2),
                extraction(200L, 2, "Withdrawn Organization", "EMPLOYMENT_CERTIFICATE"));
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID)).thenReturn(List.of(
                superseded, withdrawn, evidence(3, "active.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString())).thenReturn(
                analysis("Northstar", "EMPLOYMENT_CERTIFICATE", null, 3));
        when(corroborationService.verify(claim))
                .thenReturn(new EmploymentCorroborationService.Result("NOT_CHECKED", false));

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("VERIFIED_FROM_EVIDENCE", claim.getStatus());
        verify(analysisService, times(1)).analyze(any(), any(), eq("active.pdf"), anyString());
        verify(extractionPersistenceService, org.mockito.Mockito.never()).findLatest(100L, 1);
        verify(extractionPersistenceService, org.mockito.Mockito.never()).findLatest(200L, 2);
    }

    @Test
    void onlyHistoricalEvidenceProducesAnEvidenceRequestInsteadOfProcessingFailure() {
        ExperienceClaimEvidence superseded = evidence(1, "superseded.pdf");
        superseded.setEvidenceStatus("SUPERSEDED");
        ExperienceClaimEvidence withdrawn = evidence(2, "withdrawn.pdf");
        withdrawn.setEvidenceStatus("WITHDRAWN");
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID))
                .thenReturn(List.of(superseded, withdrawn));

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", claim.getStatus());
        assertEquals("INCONCLUSIVE", claim.getAiStatus());
        assertEquals("NOT_RUN", claim.getAnalysisMethod());
        assertTrue(claim.getDecisionExplanation().contains("No active supporting document"));
        verify(analysisService, org.mockito.Mockito.never()).analyze(any(), any(), anyString(), anyString());
        verify(extractionPersistenceService, org.mockito.Mockito.never()).findLatest(any(), any());
    }

    @Test
    void persistedClaimTypeIsPassedToDocumentAnalysis() {
        claim.setClaimType(ExperienceClaimType.MENTORSHIP);
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID))
                .thenReturn(List.of(evidence(1, "mentorship.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString())).thenReturn(
                analysis("Northstar", "MENTORSHIP_CERTIFICATE", null, 1));
        var requestCaptor = org.mockito.ArgumentCaptor.forClass(ExperienceEvidenceAnalysisRequest.class);

        processingService.processClaim(CLAIM_ID, 1);

        verify(analysisService).analyze(requestCaptor.capture(), any(), eq("mentorship.pdf"), anyString());
        assertEquals(ExperienceClaimType.MENTORSHIP, requestCaptor.getValue().claimType());
    }

    @Test
    void staleProcessingJobCannotDecideForANewerEvidenceVersion() {
        job.setEvidenceVersion(0);

        processingService.processClaim(CLAIM_ID, 1);

        verify(evidenceRepository, org.mockito.Mockito.never())
                .findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID);
        verify(analysisService, org.mockito.Mockito.never()).analyze(any(), any(), anyString(), anyString());
        verifyNoExtractionPersistence();
        assertEquals("PROCESSING", claim.getStatus());
    }

    @Test
    void doesNotUseAnExtractionAttachedToAnotherEvidenceRecord() {
        ExperienceClaimEvidence evidence = evidence(1, "certificate.pdf");
        StructuredEvidenceExtraction extraction = extraction(999L, 1, "Northstar", "EXPERIENCE_CERTIFICATE");
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID))
                .thenReturn(List.of(evidence));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(new ExperienceEvidenceAnalysisService.AnalysisResult(
                        "ANALYZED", "[]", "EXPERIENCE_CERTIFICATE", 0,
                        "HEURISTIC_FALLBACK", "NOT_NEEDED", null, extraction));

        processingService.processClaim(CLAIM_ID, 1);

        verify(extractionPersistenceService).persist(evidence, extraction);
        verify(extractionPersistenceService).findLatest(100L, 1);
        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", claim.getStatus());
    }

    @Test
    void failedReanalysisDoesNotReplaceOrClearAnEarlierSuccessfulExtraction() {
        job.setAttemptCount(1);
        ExperienceClaimEvidence evidence = evidence(1, "certificate.pdf");
        ExperienceEvidenceAnalysisService.AnalysisResult successfulAnalysis =
                analysis("Northstar", "EXPERIENCE_CERTIFICATE", null, 1);
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID))
                .thenReturn(List.of(evidence));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(successfulAnalysis)
                .thenReturn(ExperienceEvidenceAnalysisService.AnalysisResult.unavailable());

        processingService.processClaim(CLAIM_ID, 1);
        assertTrue(persistedExtractions.containsKey(new EvidenceIdentity(100L, 1)));
        processingService.processClaim(CLAIM_ID, 1);

        verify(extractionPersistenceService, times(1)).persist(evidence, successfulAnalysis.extraction());
        assertTrue(persistedExtractions.containsKey(new EvidenceIdentity(100L, 1)));
    }

    @Test
    void legacyAnalysisFindingsWithoutPersistedExtractionCannotSupportADecision() {
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID))
                .thenReturn(List.of(evidence(1, "certificate.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(new ExperienceEvidenceAnalysisService.AnalysisResult(
                        "ANALYZED",
                        "[{\"field\":\"company\",\"outcome\":\"MATCH\"}]",
                        "EMPLOYMENT_CERTIFICATE",
                        0));

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", claim.getStatus());
        assertTrue(claim.getDecisionExplanation().contains("persisted extraction"));
        verify(extractionPersistenceService).findLatest(100L, 1);
    }

    @Test
    void duplicateExtractedTextIsReportedWithoutChangingTheEvidenceDecision() throws Exception {
        claim.setEvidenceVersion(2);
        job.setEvidenceVersion(2);
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID)).thenReturn(List.of(
                evidence(1, "certificate.pdf"),
                evidence(2, "copy.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(analysis("Northstar", "EMPLOYMENT_CERTIFICATE", "a".repeat(64), 1))
                .thenReturn(analysis("Northstar", "EMPLOYMENT_CERTIFICATE", "a".repeat(64), 2));
        when(corroborationService.verify(claim))
                .thenReturn(new EmploymentCorroborationService.Result("NOT_CHECKED", false));

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("VERIFIED_FROM_EVIDENCE", claim.getStatus());
        JsonNode persistedFindings = objectMapper.readTree(claim.getAiFindings());
        JsonNode duplicate = null;
        for (JsonNode finding : persistedFindings) {
            if (finding.path("field").asText().equals("duplicateDocument")) {
                duplicate = finding;
                break;
            }
        }
        assertTrue(duplicate != null);
        assertEquals(2, duplicate.path("evidenceVersions").size());
    }

    @Test
    void unavailableAnalysisSchedulesRetryWithoutProducingAVerificationDecision() {
        job.setAttemptCount(1);
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID))
                .thenReturn(List.of(evidence(1, "certificate.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(ExperienceEvidenceAnalysisService.AnalysisResult.unavailable());

        processingService.processClaim(CLAIM_ID, 1);

        assertEquals("PROCESSING", claim.getStatus());
        assertEquals("PROCESSING", claim.getAiStatus());
        assertEquals("NOT_CHECKED", claim.getCorroborationStatus());
        assertTrue(job.getNextAttemptAt().isAfter(LocalDateTime.now()));
        assertEquals("The analysis service is temporarily unavailable.", job.getLastError());
        verifyNoExtractionPersistence();
        verify(processingRepository).save(job);
    }

    private void verifyNoExtractionPersistence() {
        verify(extractionPersistenceService, org.mockito.Mockito.never()).persist(any(), any());
    }

    private ExperienceClaimEvidence evidence(int version, String filename) {
        ExperienceClaimEvidence evidence = new ExperienceClaimEvidence(
                CLAIM_ID, version, "storage-" + version, new byte[]{1}, filename,
                "application/pdf", 1L, "hash-" + version);
        ReflectionTestUtils.setField(evidence, "id", (long) version * 100);
        return evidence;
    }

    private ExperienceEvidenceAnalysisService.AnalysisResult analysis(
            String company, String documentType, String fingerprint, int version) {
        String findings = """
                [
                  {"field":"employeeName","outcome":"MATCH","claimValue":"Example User","extractedValue":"Example User"},
                  {"field":"company","outcome":"MATCH","claimValue":"Northstar","extractedValue":"%s"},
                  {"field":"jobTitle","outcome":"MATCH","claimValue":"Engineer","extractedValue":"Engineer"}
                ]
                """.formatted(company);
        return new ExperienceEvidenceAnalysisService.AnalysisResult(
                "ANALYZED", findings, documentType, 0, "LLM_ANALYSIS", "NOT_NEEDED", fingerprint,
                extraction((long) version * 100, version, company, documentType));
    }

    private StructuredEvidenceExtraction extraction(
            long evidenceId, int version, String company, String documentType) {
        List<StructuredEvidenceExtraction.Field> fields = List.of(
                field(evidenceId, version, 0, "personName", "Example User"),
                field(evidenceId, version, 1, "organisation", company),
                field(evidenceId, version, 2, "role", "Engineer"));
        StructuredEvidenceExtraction.Provenance provenance = provenance(evidenceId, version);
        List<StructuredEvidenceExtraction.Provenance> classificationProvenance =
                "UNKNOWN".equals(documentType) ? List.of() : List.of(provenance);
        return new StructuredEvidenceExtraction(
                "mentorax.document-understanding.v1",
                evidenceId,
                version,
                "a".repeat(64),
                "b".repeat(64),
                "COMPLETE",
                documentType,
                "UNKNOWN".equals(documentType) ? BigDecimal.ZERO : new BigDecimal("0.92"),
                classificationProvenance,
                "NOT_NEEDED",
                fields,
                List.of(),
                List.of());
    }

    private StructuredEvidenceExtraction.Field field(
            long evidenceId, int version, int ordinal, String name, String value) {
        return new StructuredEvidenceExtraction.Field(
                ordinal, name, "FOUND", value, List.of(), new BigDecimal("0.91"), null,
                "LABEL_RULE", List.of(provenance(evidenceId, version)));
    }

    private StructuredEvidenceExtraction.Provenance provenance(long evidenceId, int version) {
        return new StructuredEvidenceExtraction.Provenance(
                evidenceId, version, 1, "Evidence text.", "PDF_TEXT", null, null);
    }

    private record EvidenceIdentity(Long evidenceId, Integer evidenceVersion) {}
}
