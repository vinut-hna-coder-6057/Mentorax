package com.alumni.alumni_connect.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeConversationContextResolverTest {
    private final KnowledgeConversationContextResolver resolver = new KnowledgeConversationContextResolver();

    @Test
    void resolvesShortFollowUpUsingOnlyTheMostRecentQuestion() {
        String resolved = resolver.resolve("What about Java?", List.of(
                new KnowledgeConversationContextResolver.Turn("ASSISTANT", "Earlier answer."),
                new KnowledgeConversationContextResolver.Turn("USER", "How can I prepare for backend interviews?"),
                new KnowledgeConversationContextResolver.Turn("ASSISTANT", "Unrelated older answer."),
                new KnowledgeConversationContextResolver.Turn("USER", "What is the weather today?")));

        assertEquals("How can I prepare for backend interviews? What about Java?", resolved);
        assertFalse(resolved.contains("weather"));
        assertFalse(resolved.contains("Unrelated"));
    }

    @Test
    void keepsIndependentTopicChangesContextFree() {
        assertEquals("What is Python?", resolver.resolve("What is Python?", List.of(
                new KnowledgeConversationContextResolver.Turn("USER", "How can I prepare for backend interviews?"))));
    }

    @Test
    void includesTheReferencedPriorAnswerPointForClarificationFollowUps() {
        String resolved = resolver.resolve("Can you explain the second point?", List.of(
                new KnowledgeConversationContextResolver.Turn("ASSISTANT",
                        "Plan:\n\n1. Learn Java basics.\n2. Build a REST API project.\n3. Practise tests."),
                new KnowledgeConversationContextResolver.Turn("USER", "How can I start backend development?")));

        assertTrue(resolved.contains("How can I start backend development?"));
        assertTrue(resolved.contains("Build a REST API project"));
        assertFalse(resolved.contains("Learn Java basics"));
    }

    @Test
    void boundsResolvedContextToTheRetrievalQueryLimit() {
        String resolved = resolver.resolve("What about Java?", List.of(
                new KnowledgeConversationContextResolver.Turn("USER", "A".repeat(500))));
        assertEquals(500, resolved.length());
        assertTrue(resolved.endsWith("What about Java?"));
    }
}
