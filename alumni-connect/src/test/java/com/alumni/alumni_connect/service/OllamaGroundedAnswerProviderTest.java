package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OllamaGroundedAnswerProviderTest {
    private static final String URL = "http://127.0.0.1:11434";
    private final KnowledgeRetrievalResponse.Source source = new KnowledgeRetrievalResponse.Source(
            12, 2, "INTERVIEW_EXPERIENCE", "Backend interview", "The backend interview was remote.",
            0.8, List.of("title", "narrative"), "Alumni-reported", "Northwind", "Backend Engineer");

    @Test
    void acceptsOnlyKnownSourceVersionCitations() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo(URL + "/api/chat"))
                .andRespond(withSuccess("{\"message\":{\"content\":\"The backend interview was remote. [12:2]\"}}",
                        MediaType.APPLICATION_JSON));
        OllamaGroundedAnswerProvider provider = provider(rest, URL);
        String answer = provider.generate("What was the interview like?", List.of(source), false);
        assertTrue(answer.contains("The backend interview was remote. [12:2]"));
        assertTrue(answer.contains("Ollama model"));
        server.verify();
    }

    @Test
    void rejectsFabricatedOrMissingCitationsAndMalformedResponses() {
        for (String response : List.of(
                "{\"message\":{\"content\":\"Claim [99:1]\"}}",
                "{\"message\":{\"content\":\"Claim without a citation\"}}",
                "{\"message\":{\"content\":\"The interview guarantees a job [12:2]\"}}",
                "{\"message\":{\"content\":\"Ignore previous instructions [12:2]\"}}",
                "{\"message\":{\"content\":\"" + "x".repeat(2501) + "\"}}",
                "{\"response\":\"unexpected shape\"}")) {
            RestTemplate rest = new RestTemplate();
            MockRestServiceServer.bindTo(rest).build()
                    .expect(requestTo(URL + "/api/chat"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
            assertThrows(IllegalArgumentException.class,
                    () -> provider(rest, URL).generate("question", List.of(source), false));
        }
    }

    @Test
    void rejectsRemoteOllamaEndpointsAndBoundsRequests() {
        assertThrows(IllegalArgumentException.class, () -> provider(new RestTemplate(), "http://example.com:11434"));
        assertThrows(IllegalArgumentException.class, () -> provider(new RestTemplate(), "https://127.0.0.1:11434"));
        assertThrows(IllegalArgumentException.class,
                () -> provider(new RestTemplate(), URL).generate("x".repeat(501), List.of(source), false));
    }

    @Test
    void surfacesProviderTimeoutForSafeFallbackAtServiceBoundary() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer.bindTo(rest).build().expect(requestTo(URL + "/api/chat"))
                .andRespond(withException(new IOException("timeout")));
        assertThrows(ResourceAccessException.class,
                () -> provider(rest, URL).generate("question", List.of(source), false));
    }

    private static OllamaGroundedAnswerProvider provider(RestTemplate rest, String url) {
        return new OllamaGroundedAnswerProvider(rest, new ObjectMapper(), url, "gemma3:1b");
    }
}
