package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimType;
import com.alumni.alumni_connect.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceVerificationDecisionEngineTest {
    private ExperienceVerificationDecisionEngine engine;
    private ExperienceClaim claim;

    @BeforeEach
    void setUp() {
        engine = new ExperienceVerificationDecisionEngine();
        claim = new ExperienceClaim();
        claim.setUser(new User("Example User", "example@example.test", "password", "STUDENT", "APPROVED"));
        claim.setCompany("Northstar");
        claim.setJobTitle("Engineer");
        claim.setStartMonth("2025-01");
        claim.setEndMonth("2025-12");
        claim.setClaimType(ExperienceClaimType.INTERNSHIP);
    }

    @Test
    void appropriateInternshipCertificateSupportsTheExplicitInternshipClaim() {
        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE", coreFields())));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
        assertTrue(decision.explanation().contains("internship claim"));
        assertTrue(decision.explanation().contains("not independently confirmed"));
        assertTrue(decision.requestedEvidence().isEmpty());
    }

    @Test
    void namedMonthAndYearMatchesTheClaimYearMonth() {
        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE",
                found("personName", "Example User"),
                found("organisation", "Northstar"),
                found("role", "Engineer"),
                found("startDate", "January 2025"),
                found("endDate", "Dec 2025"))));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
        assertTrue(decision.explanation().contains("consistent"));
    }

    @Test
    void mentorshipEvidenceSupportsTheExplicitMentorshipClaim() {
        claim.setClaimType(ExperienceClaimType.MENTORSHIP);

        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "MENTOR_CONFIRMATION", coreFields())));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
        assertTrue(decision.explanation().contains("mentorship claim"));
    }

    @Test
    void unspecifiedClaimDoesNotInferInternshipOrMentorshipFromClassification() {
        claim.setClaimType(ExperienceClaimType.UNSPECIFIED);

        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE", coreFields())));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("claim type remains unspecified"));
        assertTrue(decision.explanation().contains("has not been classified as an internship or mentorship"));
    }

    @Test
    void genericEvidenceCanSupportUnspecifiedClaimWithoutAssigningAType() {
        claim.setClaimType(ExperienceClaimType.UNSPECIFIED);

        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "EXPERIENCE_CERTIFICATE", coreFields())));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
        assertTrue(decision.explanation().contains("no internship or mentorship type was inferred"));
    }

    @Test
    void complementaryActiveEvidenceCanSupplyDetailsMissingFromPrimaryCertificate() {
        var primary = evidence(1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE",
                found("personName", "Example User"),
                found("organisation", "Northstar"),
                found("startDate", "2025-01-15"),
                found("endDate", "2025-12"));
        var supporting = evidence(2, 2, "ACTIVE", "ATTENDANCE_RECORD", found("role", "Engineer"));

        var decision = engine.decideEvidenceBased(claim, List.of(primary, supporting));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
        assertTrue(decision.explanation().contains("Complementary supporting evidence"));
    }

    @Test
    void conflictingActiveEvidenceRemainsVisibleAsAnInconsistencyNotFraud() {
        var primary = evidence(1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE", coreFields());
        var conflicting = evidence(2, 2, "ACTIVE", "PAYSLIP", found("organisation", "Different Organization"));

        var decision = engine.decideEvidenceBased(claim, List.of(primary, conflicting));

        assertEquals("INCONSISTENT", decision.status());
        assertTrue(decision.explanation().contains("not a finding of fraud"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("organization")));
    }

    @Test
    void declaredConflictBetweenActiveEvidenceCannotBeAssessedAsVerified() {
        var primary = evidence(1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE", coreFields());
        var conflicting = new ExperienceVerificationDecisionEngine.EvidenceInput(
                2L,
                2,
                "ACTIVE",
                extraction(2, 2, "INTERNSHIP_CERTIFICATE", "COMPLETE", "NOT_NEEDED",
                        coreFields(), List.of()),
                "CONFLICTS_WITH",
                1L);

        var decision = engine.decideEvidenceBased(claim, List.of(primary, conflicting));

        assertEquals("INCONSISTENT", decision.status());
        assertTrue(decision.explanation().contains("explicitly marked as conflicting"));
        assertTrue(decision.explanation().contains("not a finding of fraud"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("clarifying")));
    }

    @Test
    void declaredConflictWithHistoricalEvidenceDoesNotAffectTheCurrentDecision() {
        var active = evidence(1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE", coreFields());
        var historical = new ExperienceVerificationDecisionEngine.EvidenceInput(
                2L,
                2,
                "SUPERSEDED",
                extraction(2, 2, "INTERNSHIP_CERTIFICATE", "COMPLETE", "NOT_NEEDED",
                        coreFields(), List.of()),
                "CONFLICTS_WITH",
                1L);

        var decision = engine.decideEvidenceBased(claim, List.of(active, historical));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
    }

    @Test
    void differencesInIssuerOrDurationRemainAnInconsistencySignalNotFraud() {
        var first = evidence(1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE",
                fieldsWith(
                found("issuer", "Northstar Human Resources"),
                found("duration", "12 months")));
        var second = evidence(2, 2, "ACTIVE", "SUPPORTING_DOCUMENT",
                fieldsWith(
                found("issuer", "Independent Training Office"),
                found("duration", "6 months")));

        var decision = engine.decideEvidenceBased(claim, List.of(first, second));

        assertEquals("INCONSISTENT", decision.status());
        assertTrue(decision.explanation().contains("issuer"));
        assertTrue(decision.explanation().contains("duration"));
        assertTrue(decision.explanation().contains("not a finding of fraud"));
    }

    @Test
    void missingExtractionIsUnavailableAndNeverFallsBackToAnotherVersion() {
        var decision = engine.decideEvidenceBased(claim, List.of(
                new ExperienceVerificationDecisionEngine.EvidenceInput(1L, 1, "ACTIVE", null)));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("successfully persisted extraction"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("another evidence version")));
    }

    @Test
    void extractionForAnotherEvidenceVersionIsRejected() {
        StructuredEvidenceExtraction stale = extraction(
                1, 1, "INTERNSHIP_CERTIFICATE", "COMPLETE", "NOT_NEEDED", coreFields(), List.of());

        assertThrows(IllegalArgumentException.class, () -> engine.decideEvidenceBased(claim, List.of(
                new ExperienceVerificationDecisionEngine.EvidenceInput(1L, 2, "ACTIVE", stale))));
    }

    @Test
    void ambiguousRequiredFieldIsInconclusiveRatherThanAMismatch() {
        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE",
                found("personName", "Example User"),
                ambiguous("organisation", "Northstar", "Northstar Labs"),
                found("role", "Engineer"),
                found("startDate", "2025-01"),
                found("endDate", "2025-12"))));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("ambiguous"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("organization")));
    }

    @Test
    void unprocessedRequiredFieldRequestsProcessableEvidenceRatherThanAClaimMismatch() {
        var unprocessedRole = new StructuredEvidenceExtraction.Field(
                0, "role", "NOT_PROCESSED", null, List.of(), null, null, "NOT_RUN", List.of());
        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE",
                found("personName", "Example User"),
                found("organisation", "Northstar"),
                unprocessedRole,
                found("startDate", "2025-01"),
                found("endDate", "2025-12"))));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("can be processed")));
        assertTrue(!decision.explanation().toLowerCase().contains("fraud"));
    }

    @Test
    void unreadableDocumentRequestsClearerEvidenceWithoutDeclaringFraud() {
        StructuredEvidenceExtraction unreadable = extraction(
                1, 1, "UNKNOWN", "UNREADABLE", "FAILED", List.of(), List.of("DOCUMENT_UNREADABLE"));

        var decision = engine.decideEvidenceBased(claim, List.of(
                new ExperienceVerificationDecisionEngine.EvidenceInput(1L, 1, "ACTIVE", unreadable)));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("unreadable"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("clearer")));
    }

    @Test
    void lowExtractionConfidenceIsNotTreatedAsMatchOrFraud() {
        var lowConfidenceRole = found("role", "Engineer", "0.2");
        var decision = engine.decideEvidenceBased(claim, List.of(evidence(
                1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE",
                found("personName", "Example User"),
                found("organisation", "Northstar"),
                lowConfidenceRole,
                found("startDate", "2025-01"),
                found("endDate", "2025-12"))));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("low extraction confidence"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("low-confidence")));
        assertTrue(!decision.explanation().toLowerCase().contains("fraud"));
    }

    @Test
    void withdrawnAndSupersededEvidenceCannotConflictWithAnActiveRecord() {
        var active = evidence(1, 1, "ACTIVE", "INTERNSHIP_CERTIFICATE", coreFields());
        var superseded = evidence(2, 2, "SUPERSEDED", "INTERNSHIP_CERTIFICATE",
                found("organisation", "Old Organization"));
        var withdrawn = evidence(3, 3, "WITHDRAWN", "INTERNSHIP_CERTIFICATE",
                found("organisation", "Withdrawn Organization"));

        var decision = engine.decideEvidenceBased(claim, List.of(active, superseded, withdrawn));

        assertEquals("VERIFIED_FROM_EVIDENCE", decision.status());
    }

    @Test
    void onlyHistoricalEvidenceDoesNotSupportCurrentAssessment() {
        var decision = engine.decideEvidenceBased(claim, List.of(
                evidence(1, 1, "SUPERSEDED", "INTERNSHIP_CERTIFICATE", coreFields()),
                evidence(2, 2, "WITHDRAWN", "INTERNSHIP_CERTIFICATE", coreFields())));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("No active supporting document"));
    }

    @Test
    void activePdfSafetyWarningRequestsAReplacementWithoutClaimingFraud() {
        StructuredEvidenceExtraction extraction = extraction(
                1, 1, "INTERNSHIP_CERTIFICATE", "COMPLETE", "NOT_NEEDED",
                coreFields(), List.of("PDF_ACTIVE_OR_EMBEDDED_CONTENT_PRESENT"));

        var decision = engine.decideEvidenceBased(claim, List.of(
                new ExperienceVerificationDecisionEngine.EvidenceInput(1L, 1, "ACTIVE", extraction)));

        assertEquals("ADDITIONAL_EVIDENCE_REQUIRED", decision.status());
        assertTrue(decision.explanation().contains("file-safety indicator"));
        assertTrue(decision.requestedEvidence().stream().anyMatch(value -> value.contains("clean copy")));
    }

    private static List<StructuredEvidenceExtraction.Field> coreFields() {
        return List.of(
                found("personName", "Example User"),
                found("organisation", "Northstar"),
                found("role", "Engineer"),
                found("startDate", "2025-01"),
                found("endDate", "2025-12"));
    }

    private static List<StructuredEvidenceExtraction.Field> fieldsWith(
            StructuredEvidenceExtraction.Field... additional) {
        List<StructuredEvidenceExtraction.Field> fields = new ArrayList<>(coreFields());
        fields.addAll(List.of(additional));
        return fields;
    }

    private static ExperienceVerificationDecisionEngine.EvidenceInput evidence(
            long evidenceId,
            int evidenceVersion,
            String evidenceStatus,
            String classification,
            StructuredEvidenceExtraction.Field... fields) {
        return evidence(evidenceId, evidenceVersion, evidenceStatus, classification, List.of(fields));
    }

    private static ExperienceVerificationDecisionEngine.EvidenceInput evidence(
            long evidenceId,
            int evidenceVersion,
            String evidenceStatus,
            String classification,
            List<StructuredEvidenceExtraction.Field> fields) {
        return new ExperienceVerificationDecisionEngine.EvidenceInput(
                evidenceId,
                evidenceVersion,
                evidenceStatus,
                extraction(evidenceId, evidenceVersion, classification, "COMPLETE", "NOT_NEEDED",
                        fields, List.of()));
    }

    private static StructuredEvidenceExtraction extraction(
            long evidenceId,
            int evidenceVersion,
            String classification,
            String analysisStatus,
            String ocrStatus,
            List<StructuredEvidenceExtraction.Field> fields,
            List<String> warnings) {
        List<StructuredEvidenceExtraction.Provenance> classificationProvenance =
                "UNKNOWN".equals(classification) ? List.of() : List.of(provenance(evidenceId, evidenceVersion));
        List<StructuredEvidenceExtraction.Field> fieldsForEvidence = fields.stream()
                .map(field -> new StructuredEvidenceExtraction.Field(
                        field.ordinal(),
                        field.name(),
                        field.status(),
                        field.value(),
                        field.candidateValues(),
                        field.confidence(),
                        field.modelConfidence(),
                        field.extractionMethod(),
                        field.provenance().stream()
                                .map(source -> new StructuredEvidenceExtraction.Provenance(
                                        evidenceId,
                                        evidenceVersion,
                                        source.pageNumber(),
                                        source.textExcerpt(),
                                        source.extractionMethod(),
                                        source.sourceConfidence(),
                                        source.boundingBox()))
                                .toList()))
                .toList();
        return new StructuredEvidenceExtraction(
                "mentorax.document-understanding.v1",
                evidenceId,
                evidenceVersion,
                "a".repeat(64),
                "b".repeat(64),
                analysisStatus,
                classification,
                "UNKNOWN".equals(classification) ? BigDecimal.ZERO : new BigDecimal("0.92"),
                classificationProvenance,
                ocrStatus,
                fieldsForEvidence,
                List.of(),
                warnings);
    }

    private static StructuredEvidenceExtraction.Field found(String name, String value) {
        return found(name, value, "0.91");
    }

    private static StructuredEvidenceExtraction.Field found(String name, String value, String confidence) {
        return new StructuredEvidenceExtraction.Field(
                0, name, "FOUND", value, List.of(), new BigDecimal(confidence), null,
                "LABEL_RULE", List.of(provenance(1, 1)));
    }

    private static StructuredEvidenceExtraction.Field ambiguous(String name, String first, String second) {
        return new StructuredEvidenceExtraction.Field(
                0, name, "AMBIGUOUS", null, List.of(first, second), null, null,
                "LABEL_RULE", List.of(provenance(1, 1)));
    }

    private static StructuredEvidenceExtraction.Provenance provenance(long evidenceId, int version) {
        return new StructuredEvidenceExtraction.Provenance(
                evidenceId, version, 1, "Example document text.", "PDF_TEXT", null, null);
    }
}
