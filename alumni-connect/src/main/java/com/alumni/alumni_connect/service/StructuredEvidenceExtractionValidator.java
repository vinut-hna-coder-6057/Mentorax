package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

final class StructuredEvidenceExtractionValidator {
    private static final Set<String> FIELD_NAMES = Set.of(
            "personName", "organisation", "programmeName", "issuer", "role", "mentorSupervisor",
            "experienceType", "startDate", "endDate", "duration", "issueDate",
            "certificateReferenceId", "verificationUrl", "completionStatement",
            "employmentStatement", "mentorshipStatement", "supportingText");
    private static final Set<String> MULTIPLE_FIELD_NAMES = Set.of(
            "completionStatement", "employmentStatement", "mentorshipStatement", "supportingText");

    boolean isValid(JsonNode body, ExperienceEvidenceAnalysisRequest request) {
        JsonNode evidenceId = body.path("evidenceId");
        JsonNode evidenceVersion = body.path("evidenceVersion");
        if (request == null || request.evidenceId() == null || request.evidenceVersion() == null
                || !evidenceId.isIntegralNumber() || !evidenceId.canConvertToLong()
                || evidenceId.asLong() != request.evidenceId()
                || !evidenceVersion.isInt() || evidenceVersion.asInt() != request.evidenceVersion()
                || body.toString().length() > 250_000) {
            return false;
        }

        JsonNode classificationProvenance = body.path("classificationProvenance");
        String classification = body.path("documentClassification").asText();
        if (!confidence(body.path("classificationConfidence"))
                || !classificationProvenance.isArray() || classificationProvenance.size() > 3
                || ("UNKNOWN".equals(classification) && !classificationProvenance.isEmpty())
                || (!"UNKNOWN".equals(classification) && classificationProvenance.isEmpty())) {
            return false;
        }
        for (JsonNode item : classificationProvenance) {
            if (!provenance(item, request, true)) return false;
        }
        if (!hasUniqueProvenance(classificationProvenance)) return false;

        JsonNode fields = body.path("fields");
        if (!fields.isArray() || fields.size() > 40) return false;
        Set<String> singleValueNames = new HashSet<>();
        for (JsonNode field : fields) {
            String name = field.path("name").asText();
            String status = field.path("status").asText();
            JsonNode value = field.path("value");
            JsonNode candidates = field.path("candidateValues");
            JsonNode provenance = field.path("provenance");
            if (!FIELD_NAMES.contains(name)
                    || (!MULTIPLE_FIELD_NAMES.contains(name) && !singleValueNames.add(name))
                    || !Set.of("FOUND", "NOT_PRESENT", "UNREADABLE", "AMBIGUOUS", "NOT_PROCESSED")
                    .contains(status)
                    || !Set.of("LABEL_RULE", "DATE_RULE", "STATEMENT_RULE", "CLASSIFIER_RULE", "NOT_RUN")
                    .contains(field.path("extractionMethod").asText())
                    || !optionalConfidence(field.path("confidence"))
                    || !optionalConfidence(field.path("modelConfidence"))
                    || (!value.isMissingNode() && !value.isNull()
                    && (!value.isTextual() || value.asText().length() > 500))
                    || !candidates.isArray() || candidates.size() > 5
                    || !provenance.isArray() || provenance.size() > 5) {
                return false;
            }
            Set<String> candidateValues = new HashSet<>();
            for (JsonNode candidate : candidates) {
                if (!candidate.isTextual() || candidate.asText().length() > 500) return false;
                candidateValues.add(candidate.asText());
            }
            if (candidateValues.size() != candidates.size()) return false;
            for (JsonNode item : provenance) {
                if (!provenance(item, request, true)) return false;
            }
            if (!hasUniqueProvenance(provenance)) return false;
            if ("FOUND".equals(status)) {
                if (!value.isTextual() || value.asText().isBlank()
                        || !candidates.isEmpty() || provenance.isEmpty()) return false;
            } else if ("AMBIGUOUS".equals(status)) {
                if ((!value.isMissingNode() && !value.isNull())
                        || candidateValues.size() < 2 || provenance.isEmpty()) return false;
            } else if ((!value.isMissingNode() && !value.isNull()) || !candidates.isEmpty()) {
                return false;
            }
            if (("NOT_PRESENT".equals(status) || "NOT_PROCESSED".equals(status))
                    && !provenance.isEmpty()) return false;
            if ("UNREADABLE".equals(body.path("analysisStatus").asText()) && "FOUND".equals(status)) {
                return false;
            }
        }

        JsonNode statements = body.path("extractedStatements");
        if (!statements.isArray() || statements.size() > 20) return false;
        for (JsonNode statement : statements) {
            JsonNode provenance = statement.path("provenance");
            if (!Set.of("COMPLETION", "EMPLOYMENT", "MENTORSHIP", "SUPPORTING")
                    .contains(statement.path("kind").asText())
                    || !statement.path("text").isTextual() || statement.path("text").asText().isBlank()
                    || statement.path("text").asText().length() > 500
                    || !provenance.isArray() || provenance.isEmpty() || provenance.size() > 3) {
                return false;
            }
            for (JsonNode item : provenance) {
                if (!provenance(item, request, true)) return false;
            }
            if (!hasUniqueProvenance(provenance)) return false;
        }

        JsonNode warnings = body.path("warnings");
        if (!warnings.isArray() || warnings.size() > 20) return false;
        for (JsonNode warning : warnings) {
            if (!warning.isTextual() || warning.asText().length() > 500) return false;
        }
        return true;
    }

    private boolean provenance(JsonNode item, ExperienceEvidenceAnalysisRequest request, boolean pageRequired) {
        JsonNode evidenceId = item.path("sourceEvidenceId");
        JsonNode evidenceVersion = item.path("evidenceVersion");
        JsonNode page = item.path("pageNumber");
        JsonNode box = item.path("boundingBox");
        return evidenceId.isIntegralNumber() && evidenceId.canConvertToLong()
                && evidenceId.asLong() == request.evidenceId()
                && evidenceVersion.isInt() && evidenceVersion.asInt() == request.evidenceVersion()
                && (!pageRequired || page.isInt())
                && (page.isMissingNode() || page.isInt() && page.asInt() >= 1 && page.asInt() <= 20)
                && item.path("textExcerpt").isTextual()
                && !item.path("textExcerpt").asText().isBlank()
                && item.path("textExcerpt").asText().length() <= 500
                && Set.of("PDF_TEXT", "OCR").contains(item.path("extractionMethod").asText())
                && optionalConfidence(item.path("sourceConfidence"))
                && (box.isMissingNode() || box.isNull() || boundingBox(box));
    }

    private boolean boundingBox(JsonNode box) {
        if (!box.isObject()) return false;
        BigDecimal x = number(box.path("x"));
        BigDecimal y = number(box.path("y"));
        BigDecimal width = number(box.path("width"));
        BigDecimal height = number(box.path("height"));
        BigDecimal one = BigDecimal.ONE;
        return x != null && y != null && width != null && height != null
                && x.signum() >= 0 && y.signum() >= 0 && width.signum() >= 0 && height.signum() >= 0
                && x.compareTo(one) <= 0 && y.compareTo(one) <= 0
                && width.compareTo(one) <= 0 && height.compareTo(one) <= 0
                && x.add(width).compareTo(new BigDecimal("1.000001")) <= 0
                && y.add(height).compareTo(new BigDecimal("1.000001")) <= 0;
    }

    private boolean hasUniqueProvenance(JsonNode provenance) {
        Set<ProvenanceIdentity> identities = new HashSet<>();
        for (JsonNode item : provenance) {
            JsonNode box = item.path("boundingBox");
            ProvenanceIdentity identity = new ProvenanceIdentity(
                    item.path("sourceEvidenceId").asLong(),
                    item.path("evidenceVersion").asInt(),
                    item.path("pageNumber").asInt(),
                    item.path("textExcerpt").asText(),
                    item.path("extractionMethod").asText(),
                    numberIdentity(item.path("sourceConfidence")),
                    box.isObject() ? numberIdentity(box.path("x")) : null,
                    box.isObject() ? numberIdentity(box.path("y")) : null,
                    box.isObject() ? numberIdentity(box.path("width")) : null,
                    box.isObject() ? numberIdentity(box.path("height")) : null);
            if (!identities.add(identity)) return false;
        }
        return true;
    }

    private String numberIdentity(JsonNode value) {
        return value.isNumber() ? value.decimalValue().stripTrailingZeros().toPlainString() : null;
    }

    private boolean optionalConfidence(JsonNode value) {
        return value.isMissingNode() || value.isNull() || confidence(value);
    }

    private boolean confidence(JsonNode value) {
        return value.isNumber() && value.decimalValue().signum() >= 0
                && value.decimalValue().compareTo(BigDecimal.ONE) <= 0;
    }

    private BigDecimal number(JsonNode value) {
        return value.isNumber() ? value.decimalValue() : null;
    }

    private record ProvenanceIdentity(
            long evidenceId,
            int evidenceVersion,
            int pageNumber,
            String textExcerpt,
            String extractionMethod,
            String sourceConfidence,
            String x,
            String y,
            String width,
            String height) {}
}
