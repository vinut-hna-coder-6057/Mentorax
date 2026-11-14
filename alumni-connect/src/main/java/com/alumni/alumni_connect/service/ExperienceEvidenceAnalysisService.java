package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class ExperienceEvidenceAnalysisService {
    private static final Logger log = LoggerFactory.getLogger(ExperienceEvidenceAnalysisService.class);
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.service-url:http://127.0.0.1:8090}")
    private String aiServiceUrl;

    @Value("${EXPERIENCE_ANALYSIS_API_TOKEN:}")
    private String apiToken;

    public ExperienceEvidenceAnalysisService(
            @Qualifier("experienceEvidenceRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult analyze(ExperienceEvidenceAnalysisRequest claim, byte[] pdf, String filename) {
        return analyze(claim, pdf, filename, MediaType.APPLICATION_PDF_VALUE);
    }

    public AnalysisResult analyze(ExperienceEvidenceAnalysisRequest claim, byte[] document,
                                  String filename, String mediaType) {
        if (claim == null || claim.evidenceId() == null || claim.evidenceVersion() == null
                || claim.evidenceId() <= 0 || claim.evidenceVersion() <= 0) {
            log.warn("Experience analysis unavailable: evidence ID and version are required");
            return AnalysisResult.unavailable();
        }
        if (apiToken == null || apiToken.isBlank()) {
            log.warn("Experience analysis unavailable: internal token is not configured");
            return AnalysisResult.unavailable();
        }
        try {
            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.parseMediaType(mediaType));
            fileHeaders.setContentDisposition(ContentDisposition.formData()
                    .name("file").filename(filename).build());
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("claim", new HttpEntity<>(objectMapper.writeValueAsString(claim), jsonHeaders));
            parts.add("file", new HttpEntity<>(new ByteArrayResource(document) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }, fileHeaders));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(apiToken);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    aiServiceUrl.replaceFirst("/+$", "") + "/experience/analyze", HttpMethod.POST,
                    new HttpEntity<>(parts, headers), JsonNode.class);
            JsonNode body = response.getBody();
            if (!isValid(body, claim)) {
                log.warn("Experience analysis unavailable: response failed contract validation");
                return AnalysisResult.unavailable();
            }
            StructuredEvidenceExtraction extraction = isStrictResponse(body)
                    ? toStructuredExtraction(body)
                    : null;
            JsonNode findings = body.path("findings");
            if (findings.isMissingNode() || !findings.isArray()) {
                findings = body.path("fieldAssessments");
            }
            if (findings.isMissingNode() || !findings.isArray()) {
                findings = extractLegacyFindings(body);
            }
            JsonNode suspiciousFeatures = body.path("suspiciousFeatures");
            if (suspiciousFeatures.isMissingNode() || !suspiciousFeatures.isArray()) {
                suspiciousFeatures = body.path("riskIndicators");
            }
            String documentType = body.path("documentType").asText();
            if (documentType.isBlank()) {
                documentType = body.path("documentClassification").asText();
            }
            String extractedTextFingerprint = body.path("extractedTextFingerprint").isTextual()
                    ? body.path("extractedTextFingerprint").asText()
                    : null;
            return new AnalysisResult(
                    "COMPLETE".equals(body.path("analysisStatus").asText()) ? "ANALYZED" :
                            "PARTIAL".equals(body.path("analysisStatus").asText()) ? "INCONCLUSIVE" :
                                    body.path("analysisStatus").asText(),
                    objectMapper.writeValueAsString(findings),
                    documentType,
                    suspiciousFeatures.isArray() ? suspiciousFeatures.size() : 0,
                    body.path("analysisMethod").asText(),
                    body.path("ocrStatus").asText(),
                    extractedTextFingerprint,
                    extraction);
        } catch (ResourceAccessException exception) {
            log.warn("Experience analysis unavailable: service connection failed");
            return AnalysisResult.unavailable();
        } catch (RestClientException exception) {
            log.warn("Experience analysis unavailable: service returned an HTTP or client error");
            return AnalysisResult.unavailable();
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("Experience analysis unavailable: request or response JSON could not be processed");
            return AnalysisResult.unavailable();
        }
    }

    private JsonNode extractLegacyFindings(JsonNode body) {
        ArrayNode findings = objectMapper.createArrayNode();
        for (JsonNode field : body.path("fields")) {
            String name = field.path("name").asText();
            String status = field.path("status").asText();
            String mappedName = switch (name) {
                case "personName" -> "employeeName";
                case "organisation" -> "company";
                case "role" -> "jobTitle";
                case "startDate" -> "startMonth";
                case "endDate" -> "endMonth";
                case "issueDate" -> "issueDate";
                default -> name;
            };
            String outcome = switch (status) {
                case "FOUND" -> "INCONCLUSIVE";
                case "NOT_PRESENT" -> "MISSING";
                default -> "INCONCLUSIVE";
            };
            ObjectNode finding = objectMapper.createObjectNode();
            finding.put("field", mappedName);
            finding.put("outcome", outcome);
            finding.put("explanation", status.equals("FOUND")
                    ? "Document content was extracted locally; it has not yet been compared with the claim."
                    : "Document content could not be confirmed reliably from the supplied evidence.");
            if (field.path("value").isTextual()) {
                finding.put("extractedValue", field.path("value").asText());
            }
            if (!field.path("provenance").isMissingNode() && field.path("provenance").isArray()) {
                JsonNode first = field.path("provenance").get(0);
                if (first != null && first.hasNonNull("pageNumber")) {
                    finding.put("pageNumber", first.path("pageNumber").asInt());
                }
                if (first != null && first.hasNonNull("textExcerpt")) {
                    finding.put("textExcerpt", first.path("textExcerpt").asText());
                }
            }
            findings.add(finding);
        }
        return findings;
    }

    private boolean isValid(JsonNode body, ExperienceEvidenceAnalysisRequest claim) {
        if (body == null || body.isNull()
                || SensitiveEvidenceContentValidator.containsSensitiveContent(body)) {
            return false;
        }
        String schemaVersion = body.path("schemaVersion").asText();
        if (!isStrictResponse(body)) {
            String legacySchema = body.path("analysisVersion").asText();
            boolean legacySupported = "mentorax-experience-analysis-v1".equals(schemaVersion)
                    || "mentorax-experience-analysis-v2".equals(schemaVersion)
                    || "2.0".equals(legacySchema)
                    || "2.0.0".equals(legacySchema)
                    || "v2".equalsIgnoreCase(legacySchema);
            if (!legacySupported || !body.path("trustedIssuerValidation").isBoolean()) {
                return false;
            }
            return validateLegacySchema(body);
        }
        return validateStrictSchema(body)
                && new StructuredEvidenceExtractionValidator().isValid(body, claim);
    }

    private boolean isStrictResponse(JsonNode body) {
        return body != null
                && "mentorax.document-understanding.v1".equals(body.path("schemaVersion").asText());
    }

    private boolean validateStrictSchema(JsonNode body) {
        if (!body.path("trustedIssuerValidation").isBoolean() || body.path("trustedIssuerValidation").asBoolean()) {
            return false;
        }
        if (!"COMPLETE".equals(body.path("analysisStatus").asText())
                && !"PARTIAL".equals(body.path("analysisStatus").asText())
                && !"UNREADABLE".equals(body.path("analysisStatus").asText())) {
            return false;
        }
        if (!java.util.Set.of("NOT_NEEDED", "SUCCESS", "PARTIAL", "FAILED")
                .contains(body.path("ocrStatus").asText())) {
            return false;
        }
        if (!body.path("evidenceId").isIntegralNumber() || !body.path("evidenceId").canConvertToLong()
                || body.path("evidenceId").asLong() <= 0
                || !body.path("evidenceVersion").isInt() || body.path("evidenceVersion").asInt() <= 0) {
            return false;
        }
        long evidenceId = body.path("evidenceId").asLong();
        int evidenceVersion = body.path("evidenceVersion").asInt();
        JsonNode fields = body.path("fields");
        if (!fields.isArray() || fields.size() > 40) {
            return false;
        }
        java.util.Set<String> validDocumentTypes = java.util.Set.of(
                "INTERNSHIP_CERTIFICATE", "MENTORSHIP_CERTIFICATE", "EXPERIENCE_CERTIFICATE",
                "EMPLOYMENT_CERTIFICATE", "OFFER_LETTER", "SELECTION_LETTER",
                "RELIEVING_LETTER", "PAYSLIP", "MENTORSHIP_ACCEPTANCE", "MENTOR_CONFIRMATION",
                "ATTENDANCE_RECORD", "PROJECT_REPORT", "EMAIL_EVIDENCE",
                "OTHER_SUPPORTING_EVIDENCE", "UNKNOWN");
        String documentType = body.path("documentClassification").asText();
        if (!validDocumentTypes.contains(documentType)) {
            return false;
        }
        if (!body.path("sha256").isTextual() || !body.path("sha256").asText().matches("[a-f0-9]{64}")) {
            return false;
        }
        if (!body.path("classificationConfidence").isNumber() || body.path("classificationConfidence").asDouble() < 0.0
                || body.path("classificationConfidence").asDouble() > 1.0) {
            return false;
        }
        JsonNode classificationProvenance = body.path("classificationProvenance");
        if (classificationProvenance.isArray()) {
            for (JsonNode item : classificationProvenance) {
                if (!item.path("sourceEvidenceId").isIntegralNumber()
                        || !item.path("sourceEvidenceId").canConvertToLong()
                        || item.path("sourceEvidenceId").asLong() != evidenceId
                        || !item.path("evidenceVersion").isInt() || item.path("evidenceVersion").asInt() != evidenceVersion
                        || !item.path("extractionMethod").isTextual()
                        || !java.util.Set.of("PDF_TEXT", "OCR").contains(item.path("extractionMethod").asText())
                        || !item.path("textExcerpt").isTextual() || item.path("textExcerpt").asText().length() > 500) {
                    return false;
                }
            }
        }
        if ("UNKNOWN".equals(documentType) && classificationProvenance.size() > 0) {
            return false;
        }
        for (JsonNode field : fields) {
            if (!field.path("name").isTextual() || !field.path("status").isTextual()) {
                return false;
            }
            if (!java.util.Set.of("FOUND", "NOT_PRESENT", "UNREADABLE", "AMBIGUOUS", "NOT_PROCESSED")
                    .contains(field.path("status").asText())) {
                return false;
            }
            JsonNode confidence = field.path("confidence");
            if (!confidence.isMissingNode() && (!confidence.isNumber() || confidence.asDouble() < 0.0 || confidence.asDouble() > 1.0)) {
                return false;
            }
            JsonNode provenance = field.path("provenance");
            if (provenance.isArray()) {
                for (JsonNode item : provenance) {
                    if (!item.path("sourceEvidenceId").isIntegralNumber()
                            || !item.path("sourceEvidenceId").canConvertToLong()
                            || item.path("sourceEvidenceId").asLong() != evidenceId
                            || !item.path("evidenceVersion").isInt() || item.path("evidenceVersion").asInt() != evidenceVersion
                            || !item.path("pageNumber").isInt() || item.path("pageNumber").asInt() < 1
                            || item.path("pageNumber").asInt() > 20 || !item.path("textExcerpt").isTextual()
                            || item.path("textExcerpt").asText().length() > 500
                            || !item.path("extractionMethod").isTextual()
                            || !java.util.Set.of("PDF_TEXT", "OCR").contains(item.path("extractionMethod").asText())) {
                        return false;
                    }
                    JsonNode sourceConfidence = item.path("sourceConfidence");
                    if (!sourceConfidence.isMissingNode() && (!sourceConfidence.isNumber() || sourceConfidence.asDouble() < 0.0 || sourceConfidence.asDouble() > 1.0)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private StructuredEvidenceExtraction toStructuredExtraction(JsonNode body)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        List<StructuredEvidenceExtraction.Field> fields = new ArrayList<>();
        int fieldOrdinal = 0;
        for (JsonNode field : body.path("fields")) {
            List<String> candidates = new ArrayList<>();
            field.path("candidateValues").forEach(candidate -> candidates.add(candidate.asText()));
            fields.add(new StructuredEvidenceExtraction.Field(
                    fieldOrdinal++,
                    field.path("name").asText(),
                    field.path("status").asText(),
                    nullableText(field.path("value")),
                    List.copyOf(candidates),
                    nullableDecimal(field.path("confidence")),
                    nullableDecimal(field.path("modelConfidence")),
                    field.path("extractionMethod").asText(),
                    readProvenance(field.path("provenance"))));
        }

        List<StructuredEvidenceExtraction.Statement> statements = new ArrayList<>();
        int statementOrdinal = 0;
        for (JsonNode statement : body.path("extractedStatements")) {
            statements.add(new StructuredEvidenceExtraction.Statement(
                    statementOrdinal++,
                    statement.path("kind").asText(),
                    statement.path("text").asText(),
                    readProvenance(statement.path("provenance"))));
        }
        List<String> warnings = new ArrayList<>();
        body.path("warnings").forEach(warning -> warnings.add(warning.asText()));

        return new StructuredEvidenceExtraction(
                body.path("schemaVersion").asText(),
                body.path("evidenceId").asLong(),
                body.path("evidenceVersion").asInt(),
                body.path("sha256").asText(),
                sha256(objectMapper.writeValueAsBytes(canonicalize(body))),
                body.path("analysisStatus").asText(),
                body.path("documentClassification").asText(),
                body.path("classificationConfidence").decimalValue(),
                readProvenance(body.path("classificationProvenance")),
                body.path("ocrStatus").asText(),
                List.copyOf(fields),
                List.copyOf(statements),
                List.copyOf(warnings));
    }

    private List<StructuredEvidenceExtraction.Provenance> readProvenance(JsonNode values) {
        List<StructuredEvidenceExtraction.Provenance> result = new ArrayList<>();
        for (JsonNode item : values) {
            JsonNode box = item.path("boundingBox");
            StructuredEvidenceExtraction.BoundingBox boundingBox = box.isObject()
                    ? new StructuredEvidenceExtraction.BoundingBox(
                            box.path("x").decimalValue(),
                            box.path("y").decimalValue(),
                            box.path("width").decimalValue(),
                            box.path("height").decimalValue())
                    : null;
            result.add(new StructuredEvidenceExtraction.Provenance(
                    item.path("sourceEvidenceId").asLong(),
                    item.path("evidenceVersion").asInt(),
                    item.path("pageNumber").asInt(),
                    item.path("textExcerpt").asText(),
                    item.path("extractionMethod").asText(),
                    nullableDecimal(item.path("sourceConfidence")),
                    boundingBox));
        }
        return List.copyOf(result);
    }

    private String nullableText(JsonNode value) {
        return value.isTextual() ? value.asText() : null;
    }

    private BigDecimal nullableDecimal(JsonNode value) {
        return value.isNumber() ? value.decimalValue() : null;
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode ordered = objectMapper.createObjectNode();
            java.util.List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) ordered.set(name, canonicalize(value.get(name)));
            return ordered;
        }
        if (value.isArray()) {
            ArrayNode ordered = objectMapper.createArrayNode();
            value.forEach(item -> ordered.add(canonicalize(item)));
            return ordered;
        }
        return value.deepCopy();
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for extraction response identity", exception);
        }
    }

    private boolean validateLegacySchema(JsonNode body) {
        if (body == null || !body.path("trustedIssuerValidation").isBoolean()) {
            return false;
        }
        String schemaVersion = body.path("schemaVersion").asText();
        String analysisVersion = body.path("analysisVersion").asText();
        boolean supportedSchema = "mentorax-experience-analysis-v1".equals(schemaVersion)
                || "mentorax-experience-analysis-v2".equals(schemaVersion)
                || "2.0".equals(analysisVersion)
                || "2.0.0".equals(analysisVersion)
                || "v2".equalsIgnoreCase(analysisVersion);
        if (!supportedSchema || body.path("trustedIssuerValidation").asBoolean()) {
            return false;
        }
        JsonNode fingerprint = body.path("extractedTextFingerprint");
        if (!fingerprint.isMissingNode() && !fingerprint.isNull()
                && (!fingerprint.isTextual()
                || !fingerprint.asText().matches("[a-f0-9]{64}"))) {
            return false;
        }
        if (!"NONE".equals(body.path("verificationDecision").asText())
                && !"VERIFICATION_NOT_AVAILABLE".equals(body.path("verificationDecision").asText())) {
            return false;
        }
        JsonNode findings = body.path("findings");
        if (findings.isMissingNode() || !findings.isArray()) {
            findings = body.path("fieldAssessments");
        }
        String documentType = body.path("documentType").asText();
        if (documentType.isBlank()) {
            documentType = body.path("documentClassification").asText();
        }
        if (!java.util.Set.of("EMPLOYMENT_LETTER", "EMPLOYMENT_CERTIFICATE",
                "PAYSLIP", "UNKNOWN", "EXPERIENCE_CERTIFICATE",
                "RELIEVING_LETTER", "OFFER_LETTER").contains(documentType)) {
            return false;
        }
        if (!java.util.Set.of("LLM_ANALYSIS", "OCR_PLUS_LLM", "HEURISTIC_FALLBACK",
                "OCR_PLUS_HEURISTIC").contains(body.path("analysisMethod").asText())
                || !java.util.Set.of("NOT_NEEDED", "SUCCESS", "FAILED")
                .contains(body.path("ocrStatus").asText())) {
            return false;
        }
        JsonNode suspiciousFeatures = body.path("suspiciousFeatures");
        if (suspiciousFeatures.isMissingNode()) {
            suspiciousFeatures = body.path("riskIndicators");
        }
        if (!suspiciousFeatures.isArray() || suspiciousFeatures.size() > 5) {
            return false;
        }
        String status = body.path("analysisStatus").asText();
        if (!("ANALYZED".equals(status) || "INCONCLUSIVE".equals(status))) {
            return false;
        }
        if (findings.isMissingNode() || !findings.isArray() || findings.size() > 20) {
            return false;
        }
        for (JsonNode feature : suspiciousFeatures) {
            if (!feature.isTextual() || feature.asText().length() > 64) return false;
        }
        for (JsonNode finding : findings) {
            if (!finding.path("field").isTextual() || finding.path("field").asText().length() > 64
                    || !finding.path("outcome").isTextual()
                    || !java.util.Set.of("MATCH", "MISMATCH", "MISSING", "INCONCLUSIVE")
                    .contains(finding.path("outcome").asText())
                    || !finding.path("explanation").isTextual()
                    || finding.path("explanation").asText().length() > 500) {
                return false;
            }
            for (String valueName : new String[]{"claimValue", "extractedValue"}) {
                JsonNode value = finding.path(valueName);
                if (!value.isMissingNode() && !value.isNull()
                        && (!value.isTextual() || value.asText().length() > 255)) return false;
            }
        }
        return true;
    }

    public record AnalysisResult(String status, String findings, String documentType,
                                 int suspiciousFeatureCount, String analysisMethod, String ocrStatus,
                                 String extractedTextFingerprint,
                                 StructuredEvidenceExtraction extraction) {
        public AnalysisResult(String status, String findings, String documentType, int suspiciousFeatureCount) {
            this(status, findings, documentType, suspiciousFeatureCount,
                    "HEURISTIC_FALLBACK", "NOT_NEEDED", null, null);
        }

        public AnalysisResult(String status, String findings, String documentType,
                              int suspiciousFeatureCount, String analysisMethod, String ocrStatus) {
            this(status, findings, documentType, suspiciousFeatureCount,
                    analysisMethod, ocrStatus, null, null);
        }

        public AnalysisResult(String status, String findings, String documentType,
                              int suspiciousFeatureCount, String analysisMethod, String ocrStatus,
                              String extractedTextFingerprint) {
            this(status, findings, documentType, suspiciousFeatureCount,
                    analysisMethod, ocrStatus, extractedTextFingerprint, null);
        }

        public static AnalysisResult unavailable() {
            return new AnalysisResult(
                    "UNAVAILABLE", null, "UNKNOWN", 0, "UNAVAILABLE", "NOT_RUN", null, null);
        }
    }
}
