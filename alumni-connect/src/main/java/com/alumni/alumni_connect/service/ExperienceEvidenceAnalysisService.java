package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            if (!isValid(body)) {
                log.warn("Experience analysis unavailable: response failed contract validation");
                return AnalysisResult.unavailable();
            }
            JsonNode findings = body.path("findings");
            if (findings.isMissingNode() || !findings.isArray()) {
                findings = body.path("fieldAssessments");
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
            return new AnalysisResult(body.path("analysisStatus").asText(),
                    objectMapper.writeValueAsString(findings),
                    documentType,
                    suspiciousFeatures.isArray() ? suspiciousFeatures.size() : 0,
                    body.path("analysisMethod").asText(),
                    body.path("ocrStatus").asText(),
                    extractedTextFingerprint);
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

    private boolean isValid(JsonNode body) {
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
                                 String extractedTextFingerprint) {
        public AnalysisResult(String status, String findings, String documentType, int suspiciousFeatureCount) {
            this(status, findings, documentType, suspiciousFeatureCount,
                    "HEURISTIC_FALLBACK", "NOT_NEEDED", null);
        }

        public AnalysisResult(String status, String findings, String documentType,
                              int suspiciousFeatureCount, String analysisMethod, String ocrStatus) {
            this(status, findings, documentType, suspiciousFeatureCount,
                    analysisMethod, ocrStatus, null);
        }

        public static AnalysisResult unavailable() {
            return new AnalysisResult(
                    "UNAVAILABLE", null, "UNKNOWN", 0, "UNAVAILABLE", "NOT_RUN", null);
        }
    }
}
