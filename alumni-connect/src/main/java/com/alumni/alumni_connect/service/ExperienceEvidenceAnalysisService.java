package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ExperienceEvidenceAnalysisService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult analyze(ExperienceEvidenceAnalysisRequest claim, byte[] pdf, String filename) {
        if (apiToken == null || apiToken.isBlank()) {
            log.warn("Experience analysis unavailable: internal token is not configured");
            return AnalysisResult.unavailable();
        }
        try {
            HttpHeaders jsonHeaders = new HttpHeaders();
            jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.APPLICATION_PDF);
            fileHeaders.setContentDisposition(ContentDisposition.formData()
                    .name("file").filename(filename).build());
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("claim", new HttpEntity<>(objectMapper.writeValueAsString(claim), jsonHeaders));
            parts.add("file", new HttpEntity<>(new ByteArrayResource(pdf) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }, fileHeaders));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.setBearerAuth(apiToken);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    aiServiceUrl + "/experience/analyze", HttpMethod.POST,
                    new HttpEntity<>(parts, headers), JsonNode.class);
            JsonNode body = response.getBody();
            if (!isValid(body)) {
                log.warn("Experience analysis unavailable: response failed contract validation");
                return AnalysisResult.unavailable();
            }
            return new AnalysisResult(body.path("analysisStatus").asText(),
                    objectMapper.writeValueAsString(body.path("findings")));
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
        if (body == null || !"mentorax-experience-analysis-v1".equals(body.path("schemaVersion").asText())
                || !body.path("trustedIssuerValidation").isBoolean()
                || body.path("trustedIssuerValidation").asBoolean()
                || !"NONE".equals(body.path("verificationDecision").asText())
                || !(body.path("analysisStatus").asText().equals("ANALYZED")
                || body.path("analysisStatus").asText().equals("INCONCLUSIVE"))
                || !body.path("findings").isArray() || body.path("findings").size() > 20) {
            return false;
        }
        for (JsonNode finding : body.path("findings")) {
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

    public record AnalysisResult(String status, String findings) {
        public static AnalysisResult unavailable() {
            return new AnalysisResult("UNAVAILABLE", null);
        }
    }
}
