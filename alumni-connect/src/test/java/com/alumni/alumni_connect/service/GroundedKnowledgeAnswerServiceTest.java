package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
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
        when(ollama.id()).thenReturn("ollama");
        when(deterministic.id()).thenReturn("deterministic");
        service = new GroundedKnowledgeAnswerService(retrieval, mentors, contributions, List.of(ollama, deterministic));
        ReflectionTestUtils.setField(service, "provider", "ollama");
        when(retrieval.retrieve(anyString(), isNull(), eq(0), eq(5)))
                .thenAnswer(invocation -> new KnowledgeRetrievalResponse(invocation.getArgument(0), 0, 5, false, List.of(source)));
        when(mentors.discover(anyString())).thenReturn(new MentorDiscoveryResponse(List.of(), "local", true));
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
}
