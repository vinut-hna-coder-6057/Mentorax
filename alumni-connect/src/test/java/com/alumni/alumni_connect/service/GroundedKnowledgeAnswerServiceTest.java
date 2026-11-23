package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroundedKnowledgeAnswerServiceTest {
    @Mock KnowledgeRetrievalService retrieval;
    @Mock MentorDiscoveryService mentors;
    @Mock KnowledgeContributionService contributions;
    @Mock GroundedAnswerProvider ollama;
    @Mock GroundedAnswerProvider deterministic;
    @Mock GroundedAnswerProvider openai;

    private GroundedKnowledgeAnswerService service;
    private final KnowledgeRetrievalResponse.Source source = new KnowledgeRetrievalResponse.Source(
            8, 3, "PROJECT_EXPERIENCE", "Backend project", "Built a Java API.", 0.8,
            List.of("title", "narrative"), "Alumni-reported", "Northwind", "Backend Engineer");
    private final KnowledgeContributionResponse eligible = new KnowledgeContributionResponse(
            8, 3, 3, "PROJECT_EXPERIENCE", "Backend project", "Built a Java API.", "Northwind",
            "Backend Engineer", null, "PUBLISHED", true, true, LocalDateTime.now(), LocalDateTime.now(),
            LocalDateTime.now(), List.of());

    @BeforeEach
    void setUp() {
        lenient().when(ollama.id()).thenReturn("ollama");
        lenient().when(ollama.isConfigured()).thenReturn(true);
        when(deterministic.id()).thenReturn("deterministic");
        service = new GroundedKnowledgeAnswerService(retrieval, mentors, contributions, List.of(ollama, deterministic));
        ReflectionTestUtils.setField(service, "provider", "ollama");
        when(retrieval.retrieve(anyString(), isNull(), eq(0), eq(5)))
                .thenAnswer(invocation -> new KnowledgeRetrievalResponse(invocation.getArgument(0), 0, 5, false, List.of(source)));
        lenient().when(mentors.discover(anyString()))
                .thenReturn(new com.alumni.alumni_connect.dto.MentorDiscoveryResponse(List.of(), "local", true));
    }

    @Test
    void providerFailureFallsBackWithoutReturningRawFailureDetails() {
        when(ollama.generate(anyString(), anyList(), anyBoolean())).thenThrow(new IllegalStateException("private response details"));
        when(deterministic.generate(anyString(), anyList(), anyBoolean())).thenReturn("Alumni report: built a Java API [8:3].");
        when(contributions.recheckAiEligible(anyList())).thenReturn(List.of(eligible));

        var response = service.answer("What project?");

        assertEquals("DETERMINISTIC_PROVIDER_FALLBACK", response.answerMode());
        assertTrue(response.answer().contains("[8:3]"));
        assertFalse(response.answer().contains("private response details"));
        verify(deterministic).generate(anyString(), anyList(), anyBoolean());
    }

    @Test
    void revocationAfterGenerationDiscardsProviderOutputBeforeServing() {
        when(ollama.generate(anyString(), anyList(), anyBoolean())).thenReturn("Revoked secret claim [8:3]");
        when(contributions.recheckAiEligible(anyList())).thenReturn(List.of());

        var response = service.answer("What project?");

        assertEquals("INSUFFICIENT_EVIDENCE", response.evidenceStatus());
        assertTrue(response.sources().isEmpty());
        assertFalse(response.answer().contains("Revoked secret claim"));
    }

    @Test
    void explicitOpenAiSelectionIsReportedOnlyWhenConfigured() {
        when(openai.id()).thenReturn("openai");
        when(openai.isConfigured()).thenReturn(false);
        when(deterministic.generate(anyString(), anyList(), anyBoolean())).thenReturn("safe fallback [8:3]");
        when(contributions.recheckAiEligible(anyList())).thenReturn(List.of(eligible));
        service = new GroundedKnowledgeAnswerService(retrieval, mentors, contributions,
                List.of(openai, deterministic));
        ReflectionTestUtils.setField(service, "provider", "openai");

        var response = service.answer("What project?");

        assertEquals("DETERMINISTIC_PROVIDER_MISCONFIGURED", response.answerMode());
        assertEquals("safe fallback [8:3]", response.answer());
        verify(openai, never()).generate(anyString(), anyList(), anyBoolean());
    }

    @Test
    void configuredOpenAiProviderIsDistinguishedFromDeterministicFallback() {
        when(openai.id()).thenReturn("openai");
        when(openai.isConfigured()).thenReturn(true);
        when(openai.generate(anyString(), anyList(), anyBoolean())).thenReturn("Built a Java API. [8:3]");
        when(contributions.recheckAiEligible(anyList())).thenReturn(List.of(eligible));
        service = new GroundedKnowledgeAnswerService(retrieval, mentors, contributions,
                List.of(openai, deterministic));
        ReflectionTestUtils.setField(service, "provider", "openai");

        var response = service.answer("What project?");

        assertEquals("OPENAI_GROUNDED", response.answerMode());
        assertEquals("Built a Java API. [8:3]", response.answer());
    }

    @Test
    void providerInsufficientEvidenceIsNotReplacedWithAnUnqualifiedAnswer() {
        when(openai.id()).thenReturn("openai");
        when(openai.isConfigured()).thenReturn(true);
        when(openai.generate(anyString(), anyList(), anyBoolean()))
                .thenThrow(new GroundedInsufficientEvidenceException());
        service = new GroundedKnowledgeAnswerService(retrieval, mentors, contributions,
                List.of(openai, deterministic));
        ReflectionTestUtils.setField(service, "provider", "openai");

        var response = service.answer("What project?");

        assertEquals("INSUFFICIENT_EVIDENCE", response.evidenceStatus());
        assertEquals("OPENAI_INSUFFICIENT_EVIDENCE", response.answerMode());
        assertTrue(response.sources().isEmpty());
        verify(deterministic, never()).generate(anyString(), anyList(), anyBoolean());
    }
}
