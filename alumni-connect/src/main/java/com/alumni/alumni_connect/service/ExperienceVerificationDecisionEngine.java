package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExperienceVerificationDecisionEngine {
    private static final String SCHEMA_VERSION = "mentorax.document-understanding.v1";
    private static final BigDecimal MIN_RELIABLE_CONFIDENCE = new BigDecimal("0.5");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})[-/.](\\d{1,2})");
    private static final Set<String> REQUIRED_FIELDS = Set.of("employeeName", "company", "jobTitle");
    private static final Map<String, String> EXTRACTED_TO_CLAIM_FIELD = Map.of(
            "personName", "employeeName",
            "organisation", "company",
            "role", "jobTitle",
            "startDate", "startMonth",
            "endDate", "endMonth");

    public Decision decide(ExperienceClaim claim, List<EvidenceInput> evidenceInputs) {
        if (claim == null) {
            throw new IllegalArgumentException("A claim is required for evidence assessment");
        }
        List<EvidenceInput> activeEvidence = evidenceInputs == null
                ? List.of()
                : evidenceInputs.stream()
                        .filter(input -> input != null && "ACTIVE".equalsIgnoreCase(input.evidenceStatus()))
                        .toList();
        if (activeEvidence.isEmpty()) {
            return needsEvidence(
                    "No active supporting document is available for assessment.",
                    List.of("Upload active evidence that supports the experience claim."));
        }

        Set<String> matchedFields = new LinkedHashSet<>();
        Set<String> mismatchedFields = new LinkedHashSet<>();
        Set<String> ambiguousFields = new LinkedHashSet<>();
        Set<String> unreadableFields = new LinkedHashSet<>();
        Set<String> missingFields = new LinkedHashSet<>();
        Set<String> unprocessedFields = new LinkedHashSet<>();
        Set<String> lowConfidenceFields = new LinkedHashSet<>();
        Map<String, Set<String>> valuesByField = new LinkedHashMap<>();
        boolean hasMissingExtraction = false;
        boolean hasUnreadableDocument = false;
        boolean hasUnsafeDocument = false;
        boolean hasPrimaryDocument = false;
        boolean hasSupportingDocument = false;

        for (EvidenceInput input : activeEvidence) {
            if (input.evidenceId() == null || input.evidenceId() <= 0 || input.evidenceVersion() <= 0) {
                throw new IllegalArgumentException("Evidence identity is required for a current assessment");
            }
            StructuredEvidenceExtraction extraction = input.extraction();
            if (extraction == null) {
                hasMissingExtraction = true;
                continue;
            }
            validatePersistedIdentity(input, extraction);
            if ("UNREADABLE".equals(extraction.analysisStatus())) {
                hasUnreadableDocument = true;
                continue;
            }

            if (isReliable(extraction.classificationConfidence())) {
                hasPrimaryDocument |= isPrimaryDocument(claim.getClaimType(), extraction.documentClassification());
                hasSupportingDocument |= isSupportingDocument(
                        claim.getClaimType(), extraction.documentClassification());
            }

            for (StructuredEvidenceExtraction.Field field : extraction.fields()) {
                String claimField = EXTRACTED_TO_CLAIM_FIELD.get(field.name());
                if (claimField == null) continue;

                switch (field.status()) {
                    case "FOUND" -> {
                        if (isLowConfidence(field)) {
                            lowConfidenceFields.add(claimField);
                            continue;
                        }
                        String extractedValue = normalizeForComparison(claimField, field.value());
                        if (extractedValue.isEmpty()) continue;
                        valuesByField.computeIfAbsent(claimField, ignored -> new LinkedHashSet<>())
                                .add(extractedValue);
                        String expectedValue = normalizeForComparison(claimField, claimValue(claim, claimField));
                        if (expectedValue.isEmpty() || expectedValue.equals(extractedValue)) {
                            matchedFields.add(claimField);
                        } else {
                            mismatchedFields.add(claimField);
                        }
                    }
                    case "AMBIGUOUS" -> ambiguousFields.add(claimField);
                    case "UNREADABLE" -> unreadableFields.add(claimField);
                    case "NOT_PRESENT" -> missingFields.add(claimField);
                    case "NOT_PROCESSED" -> unprocessedFields.add(claimField);
                    default -> throw new IllegalArgumentException("Persisted extraction has an unsupported field status");
                }
            }

            if ("FAILED".equals(extraction.ocrStatus())) {
                hasUnreadableDocument = true;
            }
            if (extraction.warnings().contains("PDF_ACTIVE_OR_EMBEDDED_CONTENT_PRESENT")) {
                hasUnsafeDocument = true;
            }
        }

        valuesByField.forEach((field, values) -> {
            if (values.size() > 1) mismatchedFields.add(field);
        });
        if (!mismatchedFields.isEmpty()) {
            List<String> requested = mismatchedFields.stream()
                    .map(ExperienceVerificationDecisionEngine::evidenceFor)
                    .toList();
            return new Decision(
                    "INCONSISTENT",
                    "Active evidence contains conflicting information for: "
                            + String.join(", ", mismatchedFields)
                            + ". This is an evidence inconsistency, not a finding of fraud. "
                            + "Add corrected or clarifying evidence.",
                    requested);
        }

        Set<String> unresolvedRequiredFields = new LinkedHashSet<>(REQUIRED_FIELDS);
        if (claim.getStartMonth() != null && !claim.getStartMonth().isBlank()) {
            unresolvedRequiredFields.add("startMonth");
        }
        if (claim.getEndMonth() != null && !claim.getEndMonth().isBlank()) {
            unresolvedRequiredFields.add("endMonth");
        }
        unresolvedRequiredFields.removeAll(matchedFields);
        ambiguousFields.retainAll(unresolvedRequiredFields);
        unreadableFields.retainAll(unresolvedRequiredFields);
        missingFields.retainAll(unresolvedRequiredFields);
        unprocessedFields.retainAll(unresolvedRequiredFields);
        lowConfidenceFields.retainAll(unresolvedRequiredFields);

        List<String> requested = new ArrayList<>();
        if (hasMissingExtraction) {
            requested.add("Reprocess or upload active evidence that can be assessed; an extraction from another evidence version was not used.");
        }
        if (hasUnreadableDocument) {
            requested.add("Upload a clearer, upright copy of any unreadable active document.");
        }
        if (hasUnsafeDocument) {
            requested.add("Upload a clean copy without embedded files or active PDF actions.");
        }
        for (String field : unresolvedRequiredFields) {
            if (ambiguousFields.contains(field)) {
                requested.add("Provide a document that clearly identifies " + fieldLabel(field) + ".");
            } else if (unreadableFields.contains(field)) {
                requested.add("Upload a clearer document showing " + fieldLabel(field) + ".");
            } else if (lowConfidenceFields.contains(field)) {
                requested.add("Provide clearer evidence for " + fieldLabel(field)
                        + "; low-confidence extraction was not treated as a match or mismatch.");
            } else if (unprocessedFields.contains(field)) {
                requested.add("Provide a document that can be processed for " + fieldLabel(field) + ".");
            } else {
                requested.add(evidenceFor(field));
            }
        }
        if (!hasPrimaryDocument) {
            requested.add(primaryDocumentRequest(claim.getClaimType()));
        }

        if (!requested.isEmpty()) {
            String explanation;
            if (hasMissingExtraction) {
                explanation = "One or more active evidence versions do not have a successfully persisted extraction and remain unavailable for this assessment.";
            } else if (hasUnreadableDocument) {
                explanation = "One or more active documents were unreadable or had failed OCR; unreadability was not treated as a mismatch.";
            } else if (hasUnsafeDocument) {
                explanation = "A submitted document raised a file-safety indicator; it was not treated as evidence of fraud.";
            } else if (claim.getClaimType() == ExperienceClaimType.UNSPECIFIED
                    && !hasPrimaryDocument) {
                explanation = "The claim type remains unspecified. The evidence has not been classified as an internship or mentorship; "
                        + "provide general experience evidence or update the claim type.";
            } else if (!ambiguousFields.isEmpty()) {
                explanation = "Some required information is ambiguous and was not treated as a match or mismatch.";
            } else if (!lowConfidenceFields.isEmpty()) {
                explanation = "Some required extracted fields had low extraction confidence and were not treated as matches or mismatches.";
            } else if (!hasPrimaryDocument) {
                explanation = "The active evidence does not yet include a document type appropriate for this claim.";
            } else {
                explanation = "The submitted active evidence does not yet establish all required claim details.";
            }
            return needsEvidence(explanation, requested.stream().distinct().toList());
        }

        String claimTypeDescription = claim.getClaimType() == ExperienceClaimType.UNSPECIFIED
                ? "the unspecified experience claim; no internship or mentorship type was inferred"
                : "the " + claim.getClaimType().name().toLowerCase(Locale.ROOT) + " claim";
        String supportingDescription = hasSupportingDocument
                ? " Complementary supporting evidence also contributes to the assessment."
                : "";
        return new Decision(
                "VERIFIED",
                "The submitted active evidence consistently supports " + claimTypeDescription
                        + " and meets the evidence-sufficiency check." + supportingDescription
                        + " This is a document assessment; Mentorax has not independently confirmed the document issuer or claim.",
                List.of());
    }

    public Decision decideEvidenceBased(ExperienceClaim claim, List<EvidenceInput> evidenceInputs) {
        Decision decision = decide(claim, evidenceInputs);
        if ("VERIFIED".equals(decision.status())) {
            return new Decision("VERIFIED_FROM_EVIDENCE", decision.explanation(), decision.requestedEvidence());
        }
        if ("NEEDS_INFO".equals(decision.status())) {
            return new Decision(
                    "ADDITIONAL_EVIDENCE_REQUIRED", decision.explanation(), decision.requestedEvidence());
        }
        return decision;
    }

    private static void validatePersistedIdentity(
            EvidenceInput input, StructuredEvidenceExtraction extraction) {
        if (!SCHEMA_VERSION.equals(extraction.schemaVersion())
                || extraction.evidenceId() != input.evidenceId()
                || extraction.evidenceVersion() != input.evidenceVersion()
                || !Set.of("COMPLETE", "PARTIAL", "UNREADABLE").contains(extraction.analysisStatus())
                || extraction.fields() == null || extraction.statements() == null
                || extraction.classificationProvenance() == null || extraction.warnings() == null) {
            throw new IllegalArgumentException("Persisted extraction does not match the active evidence version");
        }
        List<StructuredEvidenceExtraction.Provenance> provenance = new ArrayList<>(
                extraction.classificationProvenance());
        for (StructuredEvidenceExtraction.Field field : extraction.fields()) {
            if (field == null || field.provenance() == null || field.candidateValues() == null) {
                throw new IllegalArgumentException("Persisted extraction contains an incomplete field");
            }
            provenance.addAll(field.provenance());
        }
        for (StructuredEvidenceExtraction.Statement statement : extraction.statements()) {
            if (statement == null || statement.provenance() == null) {
                throw new IllegalArgumentException("Persisted extraction contains an incomplete statement");
            }
            provenance.addAll(statement.provenance());
        }
        if (provenance.stream().anyMatch(item -> item == null
                || item.sourceEvidenceId() != input.evidenceId()
                || item.evidenceVersion() != input.evidenceVersion())) {
            throw new IllegalArgumentException("Persisted extraction provenance does not match its evidence version");
        }
    }

    private static boolean isPrimaryDocument(ExperienceClaimType claimType, String classification) {
        return switch (claimType) {
            case INTERNSHIP -> Set.of(
                    "INTERNSHIP_CERTIFICATE", "EXPERIENCE_CERTIFICATE", "EMPLOYMENT_CERTIFICATE")
                    .contains(classification);
            case MENTORSHIP -> Set.of(
                    "MENTORSHIP_CERTIFICATE", "MENTOR_CONFIRMATION", "MENTORSHIP_ACCEPTANCE")
                    .contains(classification);
            case UNSPECIFIED -> Set.of("EXPERIENCE_CERTIFICATE", "EMPLOYMENT_CERTIFICATE")
                    .contains(classification);
        };
    }

    private static boolean isSupportingDocument(ExperienceClaimType claimType, String classification) {
        return switch (claimType) {
            case INTERNSHIP -> Set.of(
                    "OFFER_LETTER", "SELECTION_LETTER", "RELIEVING_LETTER", "PAYSLIP",
                    "ATTENDANCE_RECORD", "PROJECT_REPORT", "EMAIL_EVIDENCE",
                    "OTHER_SUPPORTING_EVIDENCE").contains(classification);
            case MENTORSHIP -> Set.of(
                    "EXPERIENCE_CERTIFICATE", "EMPLOYMENT_CERTIFICATE", "ATTENDANCE_RECORD",
                    "PROJECT_REPORT", "EMAIL_EVIDENCE", "OTHER_SUPPORTING_EVIDENCE")
                    .contains(classification);
            case UNSPECIFIED -> Set.of(
                    "OFFER_LETTER", "SELECTION_LETTER", "RELIEVING_LETTER", "PAYSLIP",
                    "ATTENDANCE_RECORD", "PROJECT_REPORT", "EMAIL_EVIDENCE",
                    "OTHER_SUPPORTING_EVIDENCE").contains(classification);
        };
    }

    private static boolean isReliable(BigDecimal confidence) {
        return confidence != null && confidence.compareTo(MIN_RELIABLE_CONFIDENCE) >= 0;
    }

    private static boolean isLowConfidence(StructuredEvidenceExtraction.Field field) {
        return isBelowThreshold(field.confidence()) || isBelowThreshold(field.modelConfidence());
    }

    private static boolean isBelowThreshold(BigDecimal confidence) {
        return confidence != null && confidence.compareTo(MIN_RELIABLE_CONFIDENCE) < 0;
    }

    private static String claimValue(ExperienceClaim claim, String field) {
        return switch (field) {
            case "employeeName" -> claim.getUser() == null ? null : claim.getUser().getName();
            case "company" -> claim.getCompany();
            case "jobTitle" -> claim.getJobTitle();
            case "startMonth" -> claim.getStartMonth();
            case "endMonth" -> claim.getEndMonth();
            default -> null;
        };
    }

    private static String normalizeForComparison(String field, String value) {
        if (value == null || value.isBlank()) return "";
        if ("startMonth".equals(field) || "endMonth".equals(field)) {
            Matcher matcher = YEAR_MONTH.matcher(value);
            if (matcher.find()) {
                int month = Integer.parseInt(matcher.group(2));
                if (month >= 1 && month <= 12) {
                    return matcher.group(1) + "-" + String.format(Locale.ROOT, "%02d", month);
                }
            }
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    private static String evidenceFor(String field) {
        return switch (field) {
            case "employeeName" -> "Provide a document showing your full name as recorded by the organization.";
            case "company" -> "Provide a document that correctly identifies the organization.";
            case "jobTitle" -> "Provide a document that supports the claimed role or job title.";
            case "startMonth" -> "Provide a document confirming the claimed start month.";
            case "endMonth" -> "Provide a document confirming the claimed end month.";
            default -> "Provide a document clarifying the conflicting " + field + " information.";
        };
    }

    private static String fieldLabel(String field) {
        return switch (field) {
            case "employeeName" -> "your full name";
            case "company" -> "the organization";
            case "jobTitle" -> "the role or job title";
            case "startMonth" -> "the claimed start month";
            case "endMonth" -> "the claimed end month";
            default -> field;
        };
    }

    private static String primaryDocumentRequest(ExperienceClaimType claimType) {
        return switch (claimType) {
            case INTERNSHIP -> "Provide an internship, experience, or employment certificate; relevant supporting evidence may supplement it.";
            case MENTORSHIP -> "Provide a mentorship certificate, mentor confirmation, or mentorship acceptance record; relevant supporting evidence may supplement it.";
            case UNSPECIFIED -> "Provide a general experience or employment certificate, or update the claim type; the document will not be used to infer internship or mentorship.";
        };
    }

    private static Decision needsEvidence(String explanation, List<String> requested) {
        return new Decision("NEEDS_INFO", explanation, requested);
    }

    public record EvidenceInput(
            Long evidenceId,
            int evidenceVersion,
            String evidenceStatus,
            StructuredEvidenceExtraction extraction) {}

    public record Decision(String status, String explanation, List<String> requestedEvidence) {}
}
