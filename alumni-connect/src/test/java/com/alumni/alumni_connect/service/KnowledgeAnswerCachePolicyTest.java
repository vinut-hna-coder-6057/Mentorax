package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeAnswerCachePolicyTest {
    private final KnowledgeAnswerCachePolicy policy = new KnowledgeAnswerCachePolicy(30, 14, 7, 3);
    private final List<KnowledgeRetrievalResponse.Source> microsoft = List.of(source(8, 3, "Microsoft", "Software Engineer Intern"));

    @Test
    void allowsOnlyRecognizedGeneralPreparationQuestions() {
        assertTrue(policy.decide("How do I prepare for Microsoft SWE internship interviews?").eligible());
        assertEquals("INTERNSHIP_PREPARATION",
                policy.decide("How do I prepare for Microsoft SWE internship interviews?").intent());
        assertEquals("CAREER_ROADMAP", policy.decide("What is a general career roadmap for a new developer?").intent());
        assertEquals("GENERAL_RESUME_GUIDANCE", policy.decide("What are general resume writing tips?").intent());
        assertFalse(policy.decide("How do I improve my resume for Microsoft?").eligible());
        assertFalse(policy.decide("I have three years of experience; what should I study for Microsoft interviews?").eligible());
        assertFalse(policy.decide("What are my chances of getting selected? My profile is attached.").eligible());
        assertFalse(policy.decide("Please verify my uploaded experience evidence.").eligible());
        assertFalse(policy.decide("What Microsoft openings are available right now?").eligible());
        assertFalse(policy.decide("Can you explain this unusual request? ").eligible());
        assertFalse(policy.decide("Ask John at john@example.com about interview preparation.").eligible());
        assertFalse(policy.decide("Call 415-555-0123 about interview preparation.").eligible());
    }

    @Test
    void normalizesOnlyCaseWhitespaceAndPunctuation() {
        assertEquals("how should i prepare for swe interview", policy.normalizeQuestion("  HOW should I prepare for SWE, interview? "));
        assertNotEquals(policy.normalizeQuestion("should I study"), policy.normalizeQuestion("should I not study"));
    }

    @Test
    void normalizedQuestionAndSourceContextProduceIsolatedKeys() {
        var first = policy.decide("How do I prepare for Microsoft SWE internship interviews?");
        var variant = policy.decide("Microsoft software engineering intern preparation roadmap?");
        String firstKey = policy.key(first, microsoft, "OPENAI_GROUNDED", "gpt-4.1-mini");
        assertNotEquals(firstKey, policy.key(variant, microsoft, "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertEquals(firstKey, policy.key(policy.decide("  HOW do I prepare for Microsoft SWE internship interviews!  "),
                microsoft, "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertEquals(firstKey, policy.key(first, microsoft, "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertEquals(64, firstKey.length());
        assertFalse(firstKey.contains("Microsoft"));
        assertNotEquals(firstKey, policy.key(first,
                List.of(microsoft.getFirst(), source(9, 3, "Google", "Software Engineer Intern")),
                "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertNotEquals(policy.key(first, List.of(microsoft.getFirst(), source(9, 3, "Google", "Software Engineer Intern")),
                        "OPENAI_GROUNDED", "gpt-4.1-mini"),
                policy.key(first, List.of(source(9, 3, "Google", "Software Engineer Intern"), microsoft.getFirst()),
                        "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertNotEquals(firstKey, policy.key(first,
                List.of(source(8, 4, "Microsoft", "Software Engineer Intern")), "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertNotEquals(firstKey, policy.key(first,
                List.of(source(9, 3, "Google", "Software Engineer Intern")), "OPENAI_GROUNDED", "gpt-4.1-mini"));
        assertNotEquals(firstKey, policy.key(first, microsoft, "OLLAMA_GROUNDED", "gemma3:1b"));
        assertNotEquals(firstKey, policy.key(first, microsoft, "OPENAI_GROUNDED", "gpt-4.2"));
    }

    @Test
    void countryAndRecruitingCycleAreIndependentKeyDimensionsAndSetTtl() {
        var base = policy.decide("How do I prepare for Microsoft SWE internship interviews?");
        var india = policy.decide("How do I prepare for Microsoft SWE internship interviews in India?");
        var cycle2027 = policy.decide("How do I prepare for Microsoft SWE internship interviews for 2027?");
        var cycle2028 = policy.decide("How do I prepare for Microsoft SWE internship interviews for 2028?");
        assertNotEquals(policy.key(base, microsoft, "openai", "gpt-4.1-mini"),
                policy.key(india, microsoft, "openai", "gpt-4.1-mini"));
        assertNotEquals(policy.key(cycle2027, microsoft, "openai", "gpt-4.1-mini"),
                policy.key(cycle2028, microsoft, "openai", "gpt-4.1-mini"));
        assertEquals(7, base.ttlDays());
        assertEquals(3, cycle2027.ttlDays());
    }

    private static KnowledgeRetrievalResponse.Source source(long id, int version, String company, String role) {
        return new KnowledgeRetrievalResponse.Source(id, version, "INTERVIEW_PREPARATION", "Intern guide",
                "Practice interviews and technical topics.", 0.8, List.of("title"), "Alumni reported", company, role);
    }
}
