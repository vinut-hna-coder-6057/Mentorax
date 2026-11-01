package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock private EmploymentCorroborationService corroborationService;
    @Mock private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ExperienceClaimProcessingService processingService;
    private ExperienceClaim claim;
    private ExperienceClaimProcessing job;

    @BeforeEach
    void setUp() {
        processingService = new ExperienceClaimProcessingService(
                claimRepository,
                evidenceRepository,
                processingRepository,
                auditRepository,
                evidenceCrypto,
                analysisService,
                new ExperienceVerificationDecisionEngine(objectMapper),
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
        when(processingRepository.findById(CLAIM_ID)).thenReturn(Optional.of(job));
        when(evidenceCrypto.decrypt(any(byte[].class))).thenReturn(new byte[]{1, 2, 3});
    }

    @Test
    void persistsEvidenceBasedDecisionAndNotifiesWithoutEmployerConfirmation() throws Exception {
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID)).thenReturn(List.of(
                evidence(1, "certificate.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString())).thenReturn(
                analysis("Northstar", "EMPLOYMENT_CERTIFICATE"));
        when(corroborationService.verify(claim))
                .thenReturn(new EmploymentCorroborationService.Result("NOT_CHECKED", false));

        processingService.processClaim(CLAIM_ID);

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
                .thenReturn(analysis("Northstar", "EMPLOYMENT_CERTIFICATE"))
                .thenReturn(analysis("Northstar Inc", "PAYSLIP"));

        processingService.processClaim(CLAIM_ID);

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
    void duplicateExtractedTextIsReportedWithoutChangingTheEvidenceDecision() throws Exception {
        claim.setEvidenceVersion(2);
        job.setEvidenceVersion(2);
        when(evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(CLAIM_ID)).thenReturn(List.of(
                evidence(1, "certificate.pdf"),
                evidence(2, "copy.pdf")));
        when(analysisService.analyze(any(), any(), anyString(), anyString()))
                .thenReturn(analysis("Northstar", "EMPLOYMENT_CERTIFICATE", "a".repeat(64)))
                .thenReturn(analysis("Northstar", "EMPLOYMENT_CERTIFICATE", "a".repeat(64)));
        when(corroborationService.verify(claim))
                .thenReturn(new EmploymentCorroborationService.Result("NOT_CHECKED", false));

        processingService.processClaim(CLAIM_ID);

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

        processingService.processClaim(CLAIM_ID);

        assertEquals("PROCESSING", claim.getStatus());
        assertEquals("PROCESSING", claim.getAiStatus());
        assertEquals("NOT_CHECKED", claim.getCorroborationStatus());
        assertTrue(job.getNextAttemptAt().isAfter(LocalDateTime.now()));
        assertEquals("The analysis service is temporarily unavailable.", job.getLastError());
        verify(processingRepository).save(job);
    }

    private ExperienceClaimEvidence evidence(int version, String filename) {
        return new ExperienceClaimEvidence(
                CLAIM_ID, version, "storage-" + version, new byte[]{1}, filename,
                "application/pdf", 1L, "hash-" + version);
    }

    private ExperienceEvidenceAnalysisService.AnalysisResult analysis(String company, String documentType) {
        return analysis(company, documentType, null);
    }

    private ExperienceEvidenceAnalysisService.AnalysisResult analysis(
            String company, String documentType, String fingerprint) {
        String findings = """
                [
                  {"field":"employeeName","outcome":"MATCH","claimValue":"Example User","extractedValue":"Example User"},
                  {"field":"company","outcome":"MATCH","claimValue":"Northstar","extractedValue":"%s"},
                  {"field":"jobTitle","outcome":"MATCH","claimValue":"Engineer","extractedValue":"Engineer"}
                ]
                """.formatted(company);
        return new ExperienceEvidenceAnalysisService.AnalysisResult(
                "ANALYZED", findings, documentType, 0, "LLM_ANALYSIS", "NOT_NEEDED", fingerprint);
    }
}
