package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KnowledgeSemanticRankerTest {
    @Test
    void submitsOnlySanitizedEvidenceToAuthenticatedPrivateReranker() {
        RestTemplate rest = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://ai.example.test/private/knowledge/rank"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("The interview included a coding round.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Private alumnus title"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private@example.test"))))
                .andRespond(withSuccess("{\"scores\":{\"12\":0.87}}", MediaType.APPLICATION_JSON));
        var ranker = new KnowledgeSemanticRanker(rest, true, "https://ai.example.test/private", "test-token");
        var source = new KnowledgeRetrievalResponse.Source(12, 3, "INTERVIEW_EXPERIENCE",
                "Private alumnus title", "The interview included a coding round. Contact private@example.test.",
                0.4, List.of("narrative"), "Alumni", "Private Company", "Private Role");

        assertEquals(0.87, ranker.scores("How did the coding round go?", List.of(source)).get(12L));
        server.verify();
    }

    @Test
    void fallsBackToLexicalRetrievalWhenNotConfiguredOrResponseIsInvalid() {
        var disabled = new KnowledgeSemanticRanker(new RestTemplate(), false, "", "");
        assertTrue(disabled.scores("question", List.of()).isEmpty());
        var insecure = new KnowledgeSemanticRanker(new RestTemplate(), true,
                "http://ai.example.test", "test-token");
        var source = new KnowledgeRetrievalResponse.Source(12, 3, "GENERAL", "title", "excerpt",
                0.4, List.of(), "Alumni", "Company", "Role");
        assertTrue(insecure.scores("question", List.of(source)).isEmpty());
    }
}
