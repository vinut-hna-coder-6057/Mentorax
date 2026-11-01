package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceVerificationDecisionEngineTest {
    private ExperienceVerificationDecisionEngine engine;
    private ExperienceClaim claim;

    @BeforeEach
    void setUp() {
        engine = new ExperienceVerificationDecisionEngine(new ObjectMapper());
        claim = new ExperienceClaim();
        claim.setCompany("Northstar");
        claim.setJobTitle("Engineer");
        claim.setStartMonth("2025-01");
        claim.setEndMonth("2025-12");
    }

    @Test
    void matchingEmploymentDocumentIsVerifiedWithExplicitCorroborationLimit() {
        var decision = engine.decide(claim, List.of(analysis(
                "EMPLOYMENT_CERTIFICATE",
                finding("employeeName", "MATCH"),
                finding("company", "MATCH"),
                finding("jobTitle", "MATCH"),
                finding("startMonth", "MATCH"),
                finding("endMonth", "MATCH"),
                finding("issuerAuthenticity", "INCONCLUSIVE"))));

        assertEquals("VERIFIED", decision.status());
        assertTrue(decision.explanation().contains("not independently confirmed"));
        assertTrue(decision.requestedEvidence().isEmpty());
    }

    @Test
    void evidenceBasedDecisionUsesExplicitNonEmployerConfirmedStatus() {
        var decision = engine.decideEvidenceBased(claim, List.of(analysis(
                "EXPERIENCE_CERTIFICATE",
                finding("employeeName", "MATCH"),
                finding("company", "MATCH"),
                finding("jobTitle", "MATCH"),
                finding("startMonth", "MATCH"),
                finding("endMonth", "MATCH"))));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
        assertTrue(decision.explanation().contains("not independently confirmed"));
    }

    @Test
    void evidenceWithMissingFieldsRequestsExplicitAdditionalEvidenceStatus() {
        var decision = engine.decideEvidenceBased(claim, List.of(analysis(
                "EMPLOYMENT_LETTER",
                finding("employeeName", "MATCH"),
                finding("company", "MATCH"),
                finding("jobTitle", "MATCH"))));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("start month")));
    }

    @Test
    void missingRequiredInformationRequestsAdditionalEvidence() {
        var decision = engine.decide(claim, List.of(analysis(
                "EMPLOYMENT_LETTER",
                finding("employeeName", "MATCH"),
                finding("company", "MATCH"),
                finding("jobTitle", "MATCH"),
                finding("startMonth", "MISSING"),
                finding("endMonth", "MISSING"))));

        assertEquals("NEEDS_INFO", decision.status());
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("start month")));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("end month")));
    }

    @Test
    void conflictingFieldsBecomeInconsistentInsteadOfAutomaticRejection() {
        var decision = engine.decide(claim, List.of(analysis(
                "EMPLOYMENT_CERTIFICATE",
                finding("employeeName", "MISMATCH"),
                finding("company", "MATCH"),
                finding("jobTitle", "MATCH"),
                finding("startMonth", "MATCH"),
                finding("endMonth", "MATCH"))));

        assertEquals("INCONSISTENT", decision.status());
        assertTrue(decision.explanation().contains("not a finding of fraud"));
    }

    @Test
    void unknownDocumentTypeCannotSupportVerification() {
        var decision = engine.decide(claim, List.of(analysis(
                "UNKNOWN",
                finding("employeeName", "MATCH"),
                finding("company", "MATCH"),
                finding("jobTitle", "MATCH"),
                finding("startMonth", "MATCH"),
                finding("endMonth", "MATCH"))));

        assertEquals("NEEDS_INFO", decision.status());
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("employment letter")));
    }

    @Test
    void suspiciousPdfFeaturesRequireAReplacementDocument() {
        var result = new ExperienceEvidenceAnalysisService.AnalysisResult(
                "ANALYZED", "[]", "EMPLOYMENT_LETTER", 1);
        assertEquals("NEEDS_INFO", engine.decide(claim, List.of(result)).status());
    }

    @Test
    void unreadableOcrRequestsAClearerUploadInsteadOfVerifying() {
        var result = new ExperienceEvidenceAnalysisService.AnalysisResult(
                "INCONCLUSIVE", "[]", "UNKNOWN", 0, "OCR_PLUS_HEURISTIC", "FAILED");

        var decision = engine.decideEvidenceBased(claim, List.of(result));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.requestedEvidence().get(0).contains("clearer, upright copy"));
    }

    @Test
    void unavailableAnalysisCannotBeUsedToMakeADecision() {
        var result = ExperienceEvidenceAnalysisService.AnalysisResult.unavailable();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> engine.decide(claim, List.of(result)));
    }

    private static ExperienceEvidenceAnalysisService.AnalysisResult analysis(
            String documentType, String... findings) {
        return new ExperienceEvidenceAnalysisService.AnalysisResult(
                "ANALYZED", "[" + String.join(",", findings) + "]", documentType, 0);
    }

    private static String finding(String field, String outcome) {
        return "{\"field\":\"" + field + "\",\"outcome\":\"" + outcome + "\"}";
    }
}
