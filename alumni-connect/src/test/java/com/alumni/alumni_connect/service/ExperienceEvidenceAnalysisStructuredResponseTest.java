package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.alumni.alumni_connect.entity.ExperienceClaimType;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExperienceEvidenceAnalysisStructuredResponseTest {
    private static final long EVIDENCE_ID = 3_000_000_123L;
    private static final int EVIDENCE_VERSION = 7;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ExperienceEvidenceAnalysisService service;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        service = new ExperienceEvidenceAnalysisService(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        ReflectionTestUtils.setField(service, "apiToken", "test-token");
    }

    @Test
    void acceptsAndMapsStrictStructuredResponseWithActualLongEvidenceIdentity() throws Exception {
        respond(strictResponse(EVIDENCE_ID, EVIDENCE_VERSION, true));

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("ANALYZED", result.status());
        assertNotNull(result.extraction());
        assertEquals(EVIDENCE_ID, result.extraction().evidenceId());
        assertEquals(EVIDENCE_VERSION, result.extraction().evidenceVersion());
        assertEquals(1, result.extraction().fields().size());
        assertEquals("Alex Example", result.extraction().fields().get(0).value());
        assertEquals(EVIDENCE_ID,
                result.extraction().fields().get(0).provenance().get(0).sourceEvidenceId());
        assertEquals("PDF_TEXT", result.extraction().fields().get(0).provenance().get(0).extractionMethod());
        assertEquals(3, result.extraction().statements().size());
        assertEquals("INCONCLUSIVE",
                new ObjectMapper().readTree(result.findings()).get(0).path("outcome").asText());
    }

    @Test
    void rejectsStrictResponseForDifferentEvidenceIdentity() {
        respond(strictResponse(EVIDENCE_ID + 1, EVIDENCE_VERSION, true));

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("UNAVAILABLE", result.status());
        assertNull(result.extraction());
    }

    @Test
    void rejectsMalformedFoundFieldInsteadOfPersistingPartialExtraction() {
        respond(strictResponse(EVIDENCE_ID, EVIDENCE_VERSION, false));

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("UNAVAILABLE", result.status());
        assertNull(result.extraction());
    }

    @Test
    void rejectsLegacyStatusValuesDespiteOtherwiseValidStrictPayload() {
        respond(strictResponse(EVIDENCE_ID, EVIDENCE_VERSION, true)
                .replace("\"analysisStatus\":\"COMPLETE\"", "\"analysisStatus\":\"ANALYZED\""));

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("UNAVAILABLE", result.status());
        assertNull(result.extraction());
    }

    @Test
    void rejectsSensitiveContentBeforeReturningOrPersistingFindings() throws Exception {
        ObjectNode response = (ObjectNode) new ObjectMapper().readTree(
                strictResponse(EVIDENCE_ID, EVIDENCE_VERSION, true));
        ((ArrayNode) response.path("warnings")).add("OTP: 735904");
        respond(response.toString());

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("UNAVAILABLE", result.status());
        assertNull(result.extraction());
    }

    @Test
    void rejectsMalformedFieldTypes() throws Exception {
        ObjectNode response = (ObjectNode) new ObjectMapper().readTree(
                strictResponse(EVIDENCE_ID, EVIDENCE_VERSION, true));
        response.put("fields", "not-an-array");
        respond(response.toString());

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("UNAVAILABLE", result.status());
        assertNull(result.extraction());
    }

    @Test
    @EnabledIfSystemProperty(named = "phase26.python.response", matches = ".+")
    void acceptsActualPythonRouteResponseThroughStrictValidator() throws Exception {
        String actualResponse = Files.readString(
                Path.of(System.getProperty("phase26.python.response")),
                StandardCharsets.UTF_8);
        respond(actualResponse);

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("ANALYZED", result.status());
        assertNotNull(result.extraction());
        assertEquals(EVIDENCE_ID, result.extraction().evidenceId());
        assertEquals(EVIDENCE_VERSION, result.extraction().evidenceVersion());
    }

    @Test
    void rejectsDuplicateProvenanceRowsForOneField() throws Exception {
        ObjectNode response = (ObjectNode) new ObjectMapper().readTree(
                strictResponse(EVIDENCE_ID, EVIDENCE_VERSION, true));
        ArrayNode provenance = (ArrayNode) response.path("fields").get(0).path("provenance");
        provenance.add(provenance.get(0).deepCopy());
        respond(response.toString());

        var result = service.analyze(request(EVIDENCE_ID, EVIDENCE_VERSION), new byte[]{1}, "record.pdf");

        assertEquals("UNAVAILABLE", result.status());
        assertNull(result.extraction());
    }

    private void respond(String response) {
        server.expect(requestTo("http://127.0.0.1:8090/experience/analyze"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private static ExperienceEvidenceAnalysisRequest request(long evidenceId, int version) {
        return new ExperienceEvidenceAnalysisRequest(
                evidenceId, version, ExperienceClaimType.UNSPECIFIED,
                "Northstar", "Mentor", null, null, "Supported a programme", "Alex Example");
    }

    private static String strictResponse(long evidenceId, int version, boolean validField) {
        String field = validField
                ? """
                  "name":"personName","status":"FOUND","value":"Alex Example",
                  "candidateValues":[],"confidence":0.91,"modelConfidence":0.87,
                  "extractionMethod":"LABEL_RULE",
                  "provenance":[{"sourceEvidenceId":%d,"evidenceVersion":%d,"pageNumber":2,
                    "textExcerpt":"Alex Example completed the programme.","extractionMethod":"PDF_TEXT",
                    "sourceConfidence":0.88,"boundingBox":{"x":0.1,"y":0.2,"width":0.3,"height":0.1}}]
                  """.formatted(evidenceId, version)
                : """
                  "name":"personName","status":"FOUND","value":"Alex Example",
                  "candidateValues":[],"confidence":0.91,"modelConfidence":0.87,
                  "extractionMethod":"LABEL_RULE","provenance":[]
                  """;
        String provenance = """
                {"sourceEvidenceId":%d,"evidenceVersion":%d,"pageNumber":1,
                 "textExcerpt":"Experience Certificate","extractionMethod":"PDF_TEXT"}
                """.formatted(evidenceId, version);
        String statement = """
                {"kind":"COMPLETION","text":"Completed the programme.","provenance":[%s]},
                {"kind":"EMPLOYMENT","text":"Worked as a mentor.","provenance":[%s]},
                {"kind":"MENTORSHIP","text":"Mentored participants.","provenance":[%s]}
                """.formatted(provenance, provenance, provenance);
        return """
                {
                  "schemaVersion":"mentorax.document-understanding.v1",
                  "analysisStatus":"COMPLETE",
                  "evidenceId":%d,
                  "evidenceVersion":%d,
                  "sha256":"%s",
                  "documentClassification":"EXPERIENCE_CERTIFICATE",
                  "classificationConfidence":0.92,
                  "classificationProvenance":[%s],
                  "ocrStatus":"NOT_NEEDED",
                  "trustedIssuerValidation":false,
                  "fields":[{%s}],
                  "extractedStatements":[%s],
                  "warnings":[]
                }
                """.formatted(evidenceId, version, "a".repeat(64), provenance, field, statement);
    }
}
