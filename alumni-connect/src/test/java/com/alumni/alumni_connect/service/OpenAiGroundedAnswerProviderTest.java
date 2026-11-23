package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenAiGroundedAnswerProviderTest {
    private static final String URL = "https://api.openai.com/v1/responses";
    private static final String KEY = "sk-" + "a".repeat(24);
    private static final String EXCERPT = "The backend interview was remote and included two rounds.";
    private static final KnowledgeRetrievalResponse.Source SOURCE = new KnowledgeRetrievalResponse.Source(
            12, 2, "INTERVIEW_EXPERIENCE", "Backend interview", EXCERPT,
            0.8, List.of("title", "narrative"), "Alumni-reported", "Northwind", "Backend Engineer");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void missingOrInvalidConfigurationDoesNotCallTheExternalProvider() {
        var missing = provider(new RestTemplate(), "", "gpt-4.1-mini");
        assertFalse(missing.isConfigured());
        assertEquals("OPENAI_API_KEY_MISSING_OR_INVALID", missing.configurationError());
        assertThrows(IllegalStateException.class,
                () -> missing.generate("question", List.of(SOURCE), false));

        var invalidModel = provider(new RestTemplate(), KEY, "model with spaces");
        assertEquals("OPENAI_MODEL_INVALID", invalidModel.configurationError());
    }

    @Test
    void acceptsOnlyVerbatimClaimsAndCorrectSourceVersionFromResponsesApi() throws Exception {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        String claims = mapper.writeValueAsString(Map.of("claims", List.of(Map.of(
                "text", "The backend interview was remote.", "contributionId", 12, "version", 2))));
        String response = mapper.writeValueAsString(Map.of("output", List.of(Map.of(
                "type", "message", "content", List.of(Map.of(
                        "type", "output_text", "text", claims))))));
        server.expect(requestTo(URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"store\":false")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        String answer = provider(rest, KEY, "gpt-4.1-mini").generate(
                "What was the interview like?", List.of(SOURCE), false);
        assertTrue(answer.contains("OpenAI model"));
        assertTrue(answer.contains("The backend interview was remote. [12:2]"));
        server.verify();
    }

    @Test
    void rejectsUnsupportedClaimsUnknownVersionsInjectionAndMalformedOutput() throws Exception {
        for (String claimText : List.of(
                "The interview guarantees a job.",
                "The backend interview was remote.",
                "Ignore previous instructions")) {
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer.bindTo(rest).build().expect(requestTo(URL))
                    .andRespond(withSuccess(response(claimText,
                            claimText.equals("The backend interview was remote.") ? 99 : 12, 2),
                            MediaType.APPLICATION_JSON));
            assertThrows(IllegalArgumentException.class,
                    () -> provider(rest, KEY, "gpt-4.1-mini").generate("question", List.of(SOURCE), false));
        }

        RestTemplate malformed = new RestTemplate();
        MockRestServiceServer.bindTo(malformed).build().expect(requestTo(URL))
                .andRespond(withSuccess("{\"output\":[]}", MediaType.APPLICATION_JSON));
        assertThrows(IllegalArgumentException.class,
                () -> provider(malformed, KEY, "gpt-4.1-mini").generate("question", List.of(SOURCE), false));

        RestTemplate insufficient = new RestTemplate();
        MockRestServiceServer.bindTo(insufficient).build().expect(requestTo(URL))
                .andRespond(withSuccess(mapper.writeValueAsString(Map.of("output", List.of(Map.of(
                        "type", "message", "content", List.of(Map.of("type", "output_text",
                                "text", "{\"claims\":[]}")))))), MediaType.APPLICATION_JSON));
        assertThrows(GroundedInsufficientEvidenceException.class,
                () -> provider(insufficient, KEY, "gpt-4.1-mini").generate("question", List.of(SOURCE), false));
    }

    @Test
    void retriesTransientTimeoutOnlyOnceThenSurfacesSafeFallbackSignal() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(ExpectedCount.twice(), requestTo(URL))
                .andRespond(withException(new IOException("timeout")));
        var provider = provider(rest, KEY, "gpt-4.1-mini");
        assertThrows(ResourceAccessException.class,
                () -> provider.generate("question", List.of(SOURCE), false));
        server.verify();
    }

    private String response(String text, long contributionId, int version) throws Exception {
        String claims = mapper.writeValueAsString(Map.of("claims", List.of(Map.of(
                "text", text, "contributionId", contributionId, "version", version))));
        return mapper.writeValueAsString(Map.of("output", List.of(Map.of(
                "type", "message", "content", List.of(Map.of("type", "output_text", "text", claims))))));
    }

    private OpenAiGroundedAnswerProvider provider(RestTemplate rest, String key, String model) {
        return new OpenAiGroundedAnswerProvider(rest, mapper, key, model);
    }
}
