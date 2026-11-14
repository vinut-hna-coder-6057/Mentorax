package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.RestTemplateConfig;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaGroundedAnswerProviderLocalTest {
    @Test
    @EnabledIfSystemProperty(named = "mentorax.test.ollama", matches = "true")
    void generatesLocallyFromSyntheticEligibleEvidence() {
        var provider = new OllamaGroundedAnswerProvider(
                new RestTemplateConfig().knowledgeAnswerRestTemplate(30000), new ObjectMapper(),
                "http://127.0.0.1:11434", "gemma3:1b");
        var source = new KnowledgeRetrievalResponse.Source(9001, 1, "INTERVIEW_EXPERIENCE",
                "Synthetic backend interview", "The synthetic backend interview was remote and included a Java API task.",
                0.9, List.of("title", "narrative"), "Alumni-reported", "ExampleCo", "Backend Engineer");
        String answer = provider.generate("Was the interview remote?", List.of(source), false);
        assertTrue(answer.contains("model-assisted"));
        assertTrue(answer.contains("[9001:1]"));
    }
}
