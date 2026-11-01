package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ExperienceVerificationDecisionEngine {
    private static final Set<String> ACCEPTED_DOCUMENT_TYPES = Set.of(
            "EMPLOYMENT_LETTER", "EMPLOYMENT_CERTIFICATE", "EXPERIENCE_CERTIFICATE",
            "PAYSLIP", "RELIEVING_LETTER", "OFFER_LETTER");

    private final ObjectMapper objectMapper;

    public ExperienceVerificationDecisionEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Decision decide(ExperienceClaim claim,
                           List<ExperienceEvidenceAnalysisService.AnalysisResult> analyses) {
        if (analyses.isEmpty()) {
            return needsEvidence(
                    "No supporting document is available for analysis.",
                    List.of("Upload an employment letter, employment certificate, or payslip."));
        }
        if (analyses.stream().anyMatch(analysis -> "FAILED".equals(analysis.ocrStatus()))) {
            return needsEvidence(
                    "At least one document could not be read clearly enough to assess.",
                    List.of("Upload a clearer, upright copy of the unreadable document."));
        }

        List<JsonNode> findings = new ArrayList<>();
        boolean recognizedDocument = false;
        boolean suspiciousDocument = false;
        for (ExperienceEvidenceAnalysisService.AnalysisResult analysis : analyses) {
            if (!"ANALYZED".equals(analysis.status()) && !"INCONCLUSIVE".equals(analysis.status())) {
                throw new IllegalArgumentException("Only validated document analyses can be decided");
            }
            recognizedDocument |= ACCEPTED_DOCUMENT_TYPES.contains(analysis.documentType());
            suspiciousDocument |= analysis.suspiciousFeatureCount() > 0;
            try {
                JsonNode parsed = objectMapper.readTree(analysis.findings());
                if (parsed == null || !parsed.isArray()) {
                    throw new IllegalArgumentException("Document findings must be an array");
                }
                parsed.forEach(findings::add);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Document findings are malformed", exception);
            }
        }

        Set<String> mismatchedFields = new LinkedHashSet<>();
        Set<String> matchedFields = new LinkedHashSet<>();
        for (JsonNode finding : findings) {
            String field = finding.path("field").asText();
            String outcome = finding.path("outcome").asText();
            if ("MISMATCH".equals(outcome)
                    && Set.of("employeeName", "company", "jobTitle", "startMonth", "endMonth")
                    .contains(field)) {
                mismatchedFields.add(field);
            }
            if ("MATCH".equals(outcome)) matchedFields.add(field);
            if ("documentSecurity".equals(field)
                    && finding.path("extractedValue").isTextual()
                    && !finding.path("extractedValue").asText().isBlank()) {
                suspiciousDocument = true;
            }
        }

        if (!mismatchedFields.isEmpty()) {
            List<String> requested = mismatchedFields.stream()
                    .map(ExperienceVerificationDecisionEngine::evidenceFor)
                    .toList();
            return new Decision("INCONSISTENT",
                    "The document text conflicts with the claim for: "
                            + String.join(", ", mismatchedFields)
                            + ". This is a document mismatch, not a finding of fraud. "
                            + "Add a corrected document or update the claim details.",
                    requested);
        }

        List<String> missing = new ArrayList<>();
        requireMatch(matchedFields, "employeeName", "A document showing your full name.", missing);
        requireMatch(matchedFields, "company", "A document identifying the employer.", missing);
        requireMatch(matchedFields, "jobTitle", "A document identifying the role or job title.", missing);
        if (claim.getStartMonth() != null) {
            requireMatch(matchedFields, "startMonth", "A document confirming the claimed start month.", missing);
        }
        if (claim.getEndMonth() != null) {
            requireMatch(matchedFields, "endMonth", "A document confirming the claimed end month.", missing);
        }
        if (!recognizedDocument) {
            missing.add("Upload an employment letter, employment certificate, or payslip.");
        }
        if (suspiciousDocument) {
            missing.add("Upload a clean copy without embedded files or active PDF actions.");
        }

        if (!missing.isEmpty()) {
            return needsEvidence(
                    suspiciousDocument
                            ? "The document could not be assessed safely or is missing required details."
                            : "The submitted documents do not yet establish all required claim details.",
                    missing.stream().distinct().toList());
        }

        return new Decision("VERIFIED",
                "The submitted document text consistently supports your name, employer, role, "
                        + "and any dates provided. This is an evidence-based document assessment; "
                        + "Mentorax has not independently confirmed employment with the employer.",
                List.of());
    }

    public Decision decideEvidenceBased(ExperienceClaim claim,
                                        List<ExperienceEvidenceAnalysisService.AnalysisResult> analyses) {
        Decision decision = decide(claim, analyses);
        if ("VERIFIED".equals(decision.status())) {
            return new Decision("VERIFIED_FROM_EVIDENCE", decision.explanation(), decision.requestedEvidence());
        }
        if ("NEEDS_INFO".equals(decision.status())) {
            return new Decision("ADDITIONAL_EVIDENCE_REQUIRED", decision.explanation(), decision.requestedEvidence());
        }
        return decision;
    }

    private static void requireMatch(Set<String> matched, String field, String requested,
                                     List<String> missing) {
        if (!matched.contains(field)) missing.add(requested);
    }

    private static String evidenceFor(String field) {
        return switch (field) {
            case "employeeName" -> "Provide a document showing your full name as recorded by the employer.";
            case "company" -> "Provide a document that correctly identifies the employer.";
            case "jobTitle" -> "Provide a document that supports the claimed role or job title.";
            case "startMonth" -> "Provide a document confirming the claimed start month.";
            case "endMonth" -> "Provide a document confirming the claimed end month.";
            default -> "Provide a document clarifying the conflicting " + field + " information.";
        };
    }

    private static Decision needsEvidence(String explanation, List<String> requested) {
        return new Decision("NEEDS_INFO", explanation, requested);
    }

    public record Decision(String status, String explanation, List<String> requestedEvidence) {}
}
