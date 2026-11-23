package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeAnswerResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroundedKnowledgeAnswerCacheTest {
    @Mock KnowledgeRetrievalService retrieval;
    @Mock MentorDiscoveryService mentors;
    @Mock KnowledgeContributionService contributions;
    @Mock GroundedAnswerProvider openai;
    @Mock GroundedAnswerProvider deterministic;
    @Mock KnowledgeAnswerCacheStore cache;

    private KnowledgeAnswerCachePolicy policy;
    private SimpleMeterRegistry meters;
    private GroundedKnowledgeAnswerService service;
    private final KnowledgeRetrievalResponse.Source source = new KnowledgeRetrievalResponse.Source(
            8, 3, "INTERVIEW_PREPARATION", "Intern guide", "Practice interviews and technical topics.",
            0.8, List.of("title"), "Alumni reported", "Microsoft", "Software Engineer Intern");
    private final KnowledgeContributionResponse eligible = new KnowledgeContributionResponse(
            8, 3, 3, "INTERVIEW_PREPARATION", "Intern guide", "Practice interviews and technical topics.",
            "Microsoft", "Software Engineer Intern", 2026, "PUBLISHED", true, true,
            LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), List.of());

    @BeforeEach
    void setUp() {
        policy = new KnowledgeAnswerCachePolicy(30, 14, 7, 3);
        meters = new SimpleMeterRegistry();
        lenient().when(openai.id()).thenReturn("openai");
        lenient().when(openai.isConfigured()).thenReturn(true);
        lenient().when(deterministic.id()).thenReturn("deterministic");
        service = new GroundedKnowledgeAnswerService(retrieval, mentors, contributions,
                List.of(openai, deterministic), cache, policy, meters);
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openAiModel", "gpt-4.1-mini");
        lenient().when(retrieval.retrieve(anyString(), isNull(), eq(0), eq(5))).thenAnswer(invocation ->
                new KnowledgeRetrievalResponse(invocation.getArgument(0), 0, 5, false, List.of(source)));
        lenient().when(contributions.recheckAiEligible(anyList())).thenReturn(List.of(eligible));
        lenient().when(contributions.recheckFreshAiEligibleKeys(anyList())).thenReturn(Set.of("8:3"));
        lenient().when(openai.generate(anyString(), anyList(), anyBoolean())).thenReturn("Practice interviews and technical topics. [8:3]");
        lenient().when(cache.find(anyString())).thenReturn(Optional.empty());
        lenient().when(cache.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
        lenient().when(cache.save(any(), anyString())).thenReturn(true);
        lenient().when(mentors.discover(anyString())).thenReturn(new MentorDiscoveryResponse(List.of(), "local", true));
    }

    @Test
    void eligibleMissGeneratesOnceAndStoresOnlyValidatedSharedFields() {
        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("ALUMNI_REPORTED_EVIDENCE", answer.evidenceStatus());
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        var saved = org.mockito.ArgumentCaptor.forClass(KnowledgeAnswerCacheStore.Entry.class);
        verify(cache).save(saved.capture(), anyString());
        assertEquals("INTERNSHIP_PREPARATION", saved.getValue().intent());
        assertEquals(policy.canonicalForm(policy.decide("How do I prepare for Microsoft SWE internship interviews?"), List.of(source)),
                saved.getValue().canonicalQuestion());
        assertEquals(List.of(new KnowledgeAnswerCacheStore.SourceRef(8, 3)), saved.getValue().sources());
        assertEquals("OPENAI_GROUNDED", saved.getValue().answerMode());
        assertTrue(saved.getValue().expiresAt().isAfter(LocalDateTime.now().plusDays(6)));
        assertTrue(saved.getValue().expiresAt().isBefore(LocalDateTime.now().plusDays(7).plusSeconds(1)));
        assertEquals(1, meters.get("mentorax.knowledge.answer.cache.writes").tag("outcome", "stored").counter().count());
    }

    @Test
    void leaseCoversTheConfiguredMaximumProviderTimeoutWithMargin() {
        ReflectionTestUtils.setField(service, "lockLeaseSeconds", 5L);
        ReflectionTestUtils.setField(service, "providerTimeoutMs", 60_000L);

        service.answer("How do I prepare for Microsoft SWE internship interviews?");

        verify(cache).tryAcquire(anyString(), anyString(), eq(java.time.Duration.ofSeconds(63)));
    }

    @Test
    void validatedCacheHitSkipsProviderAndPreservesCurrentAttribution() {
        String question = "How do I prepare for Microsoft SWE internship interviews?";
        var cached = entry(question, "Practice interviews and technical topics. [8:3]");
        when(cache.find(anyString())).thenReturn(Optional.of(cached));

        var answer = service.answer(question);

        verify(openai, never()).generate(anyString(), anyList(), anyBoolean());
        verify(cache).recordHit(cached.cacheKey());
        assertEquals(List.of(source), answer.sources());
        assertEquals("OPENAI_GROUNDED", answer.answerMode());
        assertEquals(1, meters.get("mentorax.knowledge.answer.cache.generations_avoided")
                .tag("outcome", "cache_hit").counter().count());
        assertTrue(meters.get("mentorax.knowledge.answer.cache.estimated_completion_tokens_saved")
                .tag("outcome", "estimate").counter().count() > 0);
    }

    @Test
    void personalAndConversationQuestionsAlwaysBypassSharedStorage() {
        service.answer("What are my chances of getting selected for Microsoft? My profile is private.");
        service.answer("How do I prepare for Microsoft SWE internship interviews?", false);

        verify(cache, never()).find(anyString());
        verify(cache, never()).tryAcquire(anyString(), anyString(), any());
        verify(cache, never()).save(any(), anyString());
        verify(openai, times(2)).generate(anyString(), anyList(), anyBoolean());
        assertEquals(2, meters.get("mentorax.knowledge.answer.cache.requests")
                .tag("outcome", "bypass").counter().count());
    }

    @Test
    void missingEligibilityClassifierFailsClosed() {
        ReflectionTestUtils.setField(service, "cachePolicy", null);

        service.answer("How do I prepare for Microsoft SWE internship interviews?");

        verify(cache, never()).find(anyString());
        verify(cache, never()).save(any(), anyString());
        assertEquals(1, meters.get("mentorax.knowledge.answer.cache.requests")
                .tag("outcome", "bypass").counter().count());
    }

    @Test
    void malformedOrOldPromptCacheEntryIsInvalidatedBeforeRegeneration() {
        String question = "How do I prepare for Microsoft SWE internship interviews?";
        var good = entry(question, "Practice interviews and technical topics. [8:3]");
        var malformed = new KnowledgeAnswerCacheStore.Entry(good.cacheKey(), good.cacheVersion(), good.intent(),
                good.canonicalQuestion(), good.answer(), good.evidenceStatus(), good.answerMode(),
                good.providerId(), good.modelId(), "old-prompt", good.sources(), good.expiresAt());
        when(cache.find(good.cacheKey())).thenReturn(Optional.of(malformed), Optional.empty());

        var answer = service.answer(question);

        verify(cache).invalidate(good.cacheKey(), "malformed_entry");
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        assertEquals("Practice interviews and technical topics. [8:3]", answer.answer());
    }

    @Test
    void invalidCachedCitationIsRejectedBeforeItCanBeReturned() {
        String question = "How do I prepare for Microsoft SWE internship interviews?";
        var malformed = entry(question, "Practice interviews and technical topics. [88:99]");
        when(cache.find(malformed.cacheKey())).thenReturn(Optional.of(malformed), Optional.empty());

        var answer = service.answer(question);

        verify(cache).invalidate(malformed.cacheKey(), "malformed_entry");
        verify(openai).generate(anyString(), anyList(), anyBoolean());
        assertEquals("Practice interviews and technical topics. [8:3]", answer.answer());
    }

    @Test
    void revocationDuringProviderGenerationPreventsReturningOrStoringOldSourceAnswer() {
        doAnswer(invocation -> {
            doReturn(List.of()).when(contributions).recheckAiEligible(anyList());
            return "Practice interviews and technical topics. [8:3]";
        }).when(openai).generate(anyString(), anyList(), anyBoolean());

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("INSUFFICIENT_EVIDENCE", answer.evidenceStatus());
        verify(cache, never()).save(any(), anyString());
    }

    @Test
    void publishingANewerVersionDuringGenerationPreventsOldVersionAnswer() {
        doAnswer(invocation -> {
            doReturn(List.of()).when(contributions).recheckAiEligible(anyList());
            return "Practice interviews and technical topics. [8:3]";
        }).when(openai).generate(anyString(), anyList(), anyBoolean());

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("INSUFFICIENT_EVIDENCE", answer.evidenceStatus());
        verify(cache, never()).save(any(), anyString());
    }

    @Test
    void revokedOrStaleSourceInvalidatesCachedAnswerBeforeFallbackGeneration() {
        String question = "How do I prepare for Microsoft SWE internship interviews?";
        String key = policy.key(policy.decide(question), List.of(source), "openai", "gpt-4.1-mini");
        when(cache.find(key)).thenReturn(Optional.of(entry(question, "Practice interviews and technical topics. [8:3]")), Optional.empty());
        when(contributions.recheckFreshAiEligibleKeys(anyList())).thenReturn(Set.of());

        var answer = service.answer(question);

        verify(cache).invalidate(key, "source_ineligible");
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        verify(cache, never()).save(any(), anyString());
        assertTrue(answer.answer().startsWith("Practice interviews"));
    }

    @Test
    void cacheDatabaseFailureFallsBackWithoutRepeatingGenerationOrLeakingError() {
        when(cache.find(anyString())).thenThrow(new IllegalStateException("database connection secret"));

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("Practice interviews and technical topics. [8:3]", answer.answer());
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        assertFalse(answer.answer().contains("database connection secret"));
    }

    @Test
    void cacheWriteFailureReturnsAlreadyGeneratedAnswerWithoutCallingProviderTwice() {
        when(cache.save(any(), anyString())).thenThrow(new IllegalStateException("cache write failed"));

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("Practice interviews and technical topics. [8:3]", answer.answer());
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        verify(cache).release(anyString(), anyString());
    }

    @Test
    void leaseAcquisitionFailureFallsBackToGroundedGeneration() {
        when(cache.tryAcquire(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("Practice interviews and technical topics. [8:3]", answer.answer());
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        verify(cache, never()).save(any(), anyString());
    }

    @Test
    void leaseReleaseFailureDoesNotDiscardTheGroundedAnswer() {
        doThrow(new IllegalStateException("release unavailable"))
                .when(cache).release(anyString(), anyString());

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("Practice interviews and technical topics. [8:3]", answer.answer());
        verify(openai, times(1)).generate(anyString(), anyList(), anyBoolean());
        verify(cache).release(anyString(), anyString());
    }

    @Test
    void providerFallbackIsNeverWrittenToSharedCache() {
        when(openai.generate(anyString(), anyList(), anyBoolean())).thenThrow(new IllegalStateException("provider failed"));
        when(deterministic.generate(anyString(), anyList(), anyBoolean())).thenReturn("Alumni report [8:3]");
        var fallback = service.answer("How do I prepare for Microsoft SWE internship interviews?");
        assertEquals("DETERMINISTIC_PROVIDER_FALLBACK", fallback.answerMode());
        verify(cache, never()).save(any(), anyString());
    }

    @Test
    void emptyProviderResponseIsNeverWrittenToSharedCache() {
        when(openai.generate(anyString(), anyList(), anyBoolean())).thenReturn("   ");

        var answer = service.answer("How do I prepare for Microsoft SWE internship interviews?");

        assertEquals("   ", answer.answer());
        verify(cache, never()).save(any(), anyString());
    }

    private KnowledgeAnswerCacheStore.Entry entry(String question, String answer) {
        String key = policy.key(policy.decide(question), List.of(source), "openai", "gpt-4.1-mini");
        return new KnowledgeAnswerCacheStore.Entry(key, KnowledgeAnswerCachePolicy.CACHE_SCHEMA_VERSION,
                "INTERNSHIP_PREPARATION", policy.canonicalForm(policy.decide(question), List.of(source)), answer, "ALUMNI_REPORTED_EVIDENCE",
                "OPENAI_GROUNDED", "openai", "gpt-4.1-mini", KnowledgeAnswerCachePolicy.PROMPT_VERSION,
                List.of(new KnowledgeAnswerCacheStore.SourceRef(8, 3)), LocalDateTime.now().plusDays(7));
    }
}
