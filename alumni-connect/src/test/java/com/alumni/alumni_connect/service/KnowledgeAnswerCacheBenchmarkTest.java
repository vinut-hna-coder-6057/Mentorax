package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Repeatable synthetic microbenchmark; no production or user data, AI provider, or database is used. */
class KnowledgeAnswerCacheBenchmarkTest {
    @Test
    void thousandQuestionWorkloadReportsMeasuredReuseAndContextGuards() throws Exception {
        Workload workload = new Workload();
        AtomicInteger baselineGenerations = new AtomicInteger();
        var baseline = buildService(workload, null, null, null,
                provider(baselineGenerations, 10));
        List<String> samples = questions();
        List<Long> baselineLatency = run(baseline, samples);

        AtomicInteger cachedGenerations = new AtomicInteger();
        InMemoryCacheStore store = new InMemoryCacheStore();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        var policy = new KnowledgeAnswerCachePolicy(30, 14, 7, 3);
        String expiredQuestion = "How do I prepare for Microsoft SWE internship interviews for 2027?";
        var expiredSource = workload.source(expiredQuestion);
        var expiredDecision = policy.decide(expiredQuestion);
        String expiredKey = policy.key(expiredDecision, List.of(expiredSource), "openai", "test-model");
        store.seed(new KnowledgeAnswerCacheStore.Entry(expiredKey, 1, expiredDecision.intent(), "old",
                "Old grounded answer [8:3]", "ALUMNI_REPORTED_EVIDENCE", "OPENAI_GROUNDED", "openai",
                "test-model", KnowledgeAnswerCachePolicy.PROMPT_VERSION,
                List.of(new KnowledgeAnswerCacheStore.SourceRef(8, 3)), LocalDateTime.now().minusSeconds(1)));
        var cached = buildService(workload, store, policy, meters, provider(cachedGenerations, 10));
        List<Long> cachedLatency = run(cached, samples);

        double eligible = meters.get("mentorax.knowledge.answer.cache.requests").tag("outcome", "eligible").counter().count();
        double hits = meters.get("mentorax.knowledge.answer.cache.requests").tag("outcome", "hit").counter().count();
        double misses = meters.get("mentorax.knowledge.answer.cache.requests").tag("outcome", "miss").counter().count();
        double bypasses = meters.get("mentorax.knowledge.answer.cache.requests").tag("outcome", "bypass").counter().count();
        double avoided = meters.get("mentorax.knowledge.answer.cache.generations_avoided").tag("outcome", "cache_hit").counter().count();
        double estimatedTokens = meters.get("mentorax.knowledge.answer.cache.estimated_completion_tokens_saved")
                .tag("outcome", "estimate").counter().count();
        int cachedEligibleGenerations = cachedGenerations.get() - (int) bypasses;
        double hitRate = hits / (double) eligible;
        double reduction = (eligible - cachedEligibleGenerations) / (double) eligible;
        long contextFalseMatches = Math.max(0, 5 - store.size());

        assertEquals(1000, samples.size());
        assertEquals(800.0, eligible, 0.001);
        assertEquals(792.0, hits, 0.001);
        assertEquals(8.0, misses, 0.001);
        assertEquals(200.0, bypasses, 0.001);
        assertEquals(1000, baselineGenerations.get());
        assertEquals(208, cachedGenerations.get());
        assertEquals(792, avoided);
        assertEquals(8, store.size());
        assertEquals(1, store.expiredCount());
        assertEquals(0, contextFalseMatches);
        assertTrue(hitRate >= 0.99);
        assertTrue(reduction >= 0.99);
        assertTrue(estimatedTokens > 0);

        System.out.printf("KNOWLEDGE_CACHE_BENCHMARK requests=%d eligible=%d hits=%d misses=%d bypasses=%d "
                        + "baselineGenerations=%d cachedGenerations=%d generationsAvoided=%d hitRate=%.4f "
                        + "generationReduction=%.4f estimatedCompletionTokensSaved=%d "
                        + "baselineAvgMs=%.3f cachedAvgMs=%.3f baselineP95Ms=%.3f cachedP95Ms=%.3f "
                        + "contextFalseMatches=%d expiredEntries=%d simulatedProviderDelayMs=10%n",
                samples.size(), Math.round(eligible), Math.round(hits), Math.round(misses), Math.round(bypasses),
                baselineGenerations.get(), cachedGenerations.get(), Math.round(avoided), hitRate, reduction,
                Math.round(estimatedTokens),
                average(baselineLatency), average(cachedLatency), percentile95(baselineLatency),
                percentile95(cachedLatency), contextFalseMatches, store.expiredCount());
    }

    @Test
    void concurrentEquivalentMissesUseOneGenerationAndBoundedWaitersReuseIt() throws Exception {
        Workload workload = new Workload();
        InMemoryCacheStore store = new InMemoryCacheStore();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AtomicInteger generations = new AtomicInteger();
        var service = buildService(workload, store, new KnowledgeAnswerCachePolicy(30, 14, 7, 3), meters,
                provider(generations, 100));
        int requests = 100;
        var executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < requests; i++) futures.add(executor.submit(() -> {
            ready.countDown();
            start.await();
            service.answer("How do I prepare for Microsoft SWE internship interviews?");
            return null;
        }));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (var future : futures) future.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(1, generations.get());
        assertEquals(requests - 1, meters.get("mentorax.knowledge.answer.cache.requests")
                .tag("outcome", "hit").counter().count());
    }

    private static List<Long> run(GroundedKnowledgeAnswerService service, List<String> samples) {
        List<Long> latencies = new ArrayList<>(samples.size());
        for (String question : samples) {
            long started = System.nanoTime();
            service.answer(question);
            latencies.add(System.nanoTime() - started);
        }
        return latencies;
    }

    private static List<String> questions() {
        List<String> result = new ArrayList<>(1000);
        List<String> equivalent = List.of(
                "How do I prepare for Microsoft SWE internship interviews?",
                "Microsoft software engineering intern preparation roadmap?",
                "What should I study for Microsoft internship interviews?",
                "Microsoft SWE intern interview prep");
        for (int i = 0; i < 500; i++) result.add(equivalent.get(i % equivalent.size()));
        for (int i = 0; i < 100; i++) result.add("How do I prepare for Microsoft SWE internship interviews for "
                + (i % 2 == 0 ? "2027" : "2028") + "?");
        for (int i = 0; i < 100; i++) result.add("How do I prepare for Microsoft SWE internship interviews in India?");
        for (int i = 0; i < 100; i++) result.add("How do I prepare for Google SWE internship interviews?");
        for (int i = 0; i < 200; i++) result.add("What are my chances of being selected for Microsoft? My profile is private.");
        return List.copyOf(result);
    }

    private static GroundedKnowledgeAnswerService buildService(Workload workload, KnowledgeAnswerCacheStore store,
            KnowledgeAnswerCachePolicy policy, SimpleMeterRegistry meters, GroundedAnswerProvider provider) {
        KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
        KnowledgeContributionService contributions = mock(KnowledgeContributionService.class);
        MentorDiscoveryService mentors = mock(MentorDiscoveryService.class);
        when(retrieval.retrieve(anyString(), isNull(), eq(0), eq(5))).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            var source = workload.source(question);
            return new KnowledgeRetrievalResponse(question, 0, 5, false, List.of(source));
        });
        when(contributions.recheckAiEligible(anyList())).thenAnswer(invocation -> {
            List<KnowledgeContributionService.ContributionVersionRef> refs = invocation.getArgument(0);
            return refs.stream().map(ref -> workload.contribution(ref.contributionId(), ref.version())).toList();
        });
        when(contributions.recheckFreshAiEligibleKeys(anyList())).thenAnswer(invocation -> {
            List<KnowledgeContributionService.ContributionVersionRef> refs = invocation.getArgument(0);
            return refs.stream().map(ref -> ref.contributionId() + ":" + ref.version()).collect(java.util.stream.Collectors.toSet());
        });
        when(mentors.discover(anyString())).thenReturn(new MentorDiscoveryResponse(List.of(), "local", true));
        var providers = List.of(provider, new DeterministicGroundedAnswerProvider());
        GroundedKnowledgeAnswerService service = store == null
                ? new GroundedKnowledgeAnswerService(retrieval, mentors, contributions, providers)
                : new GroundedKnowledgeAnswerService(retrieval, mentors, contributions, providers, store, policy, meters);
        ReflectionTestUtils.setField(service, "provider", provider.id());
        ReflectionTestUtils.setField(service, "openAiModel", "test-model");
        return service;
    }

    private static GroundedAnswerProvider provider(AtomicInteger generations, int delayMs) {
        GroundedAnswerProvider provider = mock(GroundedAnswerProvider.class);
        when(provider.id()).thenReturn("openai");
        when(provider.isConfigured()).thenReturn(true);
        when(provider.generate(anyString(), anyList(), anyBoolean())).thenAnswer(invocation -> {
            generations.incrementAndGet();
            if (delayMs > 0) Thread.sleep(delayMs);
            var source = (KnowledgeRetrievalResponse.Source) ((List<?>) invocation.getArgument(1)).getFirst();
            return "Practice interviews and technical topics. [" + source.contributionId() + ":" + source.version() + "]";
        });
        return provider;
    }

    private static double average(List<Long> times) {
        return times.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
    }

    private static double percentile95(List<Long> times) {
        List<Long> sorted = times.stream().sorted(Comparator.naturalOrder()).toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000.0;
    }

    private static final class Workload {
        KnowledgeRetrievalResponse.Source source(String question) {
            boolean google = question.toLowerCase().contains("google");
            long id = google ? 9 : 8;
            String company = google ? "Google" : "Microsoft";
            return new KnowledgeRetrievalResponse.Source(id, 3, "INTERVIEW_PREPARATION", "Intern guide",
                    "Practice interviews and technical topics.", 0.8, List.of("title"), "Alumni reported", company,
                    "Software Engineer Intern");
        }

        KnowledgeContributionResponse contribution(long id, int version) {
            String company = id == 9 ? "Google" : "Microsoft";
            return new KnowledgeContributionResponse(id, version, version, "INTERVIEW_PREPARATION", "Intern guide",
                    "Practice interviews and technical topics.", company, "Software Engineer Intern", 2026,
                    "PUBLISHED", true, true, LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now(), List.of());
        }
    }

    private static final class InMemoryCacheStore implements KnowledgeAnswerCacheStore {
        private final Map<String, Entry> entries = new ConcurrentHashMap<>();
        private final Map<String, Lease> locks = new ConcurrentHashMap<>();
        private final AtomicLong expired = new AtomicLong();

        @Override public Optional<Entry> find(String key) {
            Entry entry = entries.get(key);
            if (entry != null && !entry.expiresAt().isAfter(LocalDateTime.now())) {
                entries.remove(key, entry);
                expired.incrementAndGet();
                return Optional.empty();
            }
            return Optional.ofNullable(entry);
        }
        @Override public void recordHit(String key) { }
        @Override public boolean tryAcquire(String key, String token, Duration lease) {
            LocalDateTime expiresAt = LocalDateTime.now().plus(lease);
            Lease created = new Lease(token, expiresAt);
            Lease existing = locks.putIfAbsent(key, created);
            if (existing == null) return true;
            return existing.expiresAt().isBefore(LocalDateTime.now()) && locks.replace(key, existing, created);
        }
        @Override public void release(String key, String token) {
            locks.computeIfPresent(key, (ignored, lease) -> lease.token().equals(token) ? null : lease);
        }
        @Override public boolean save(Entry entry, String token) {
            Lease lease = locks.get(entry.cacheKey());
            if (lease == null || !lease.token().equals(token) || !lease.expiresAt().isAfter(LocalDateTime.now())) return false;
            entries.put(entry.cacheKey(), entry);
            return true;
        }
        @Override public void invalidate(String key, String reason) { entries.remove(key); }
        @Override public void invalidateContribution(long id, String reason) {
            entries.entrySet().removeIf(item -> item.getValue().sources().stream()
                    .anyMatch(source -> source.contributionId() == id));
        }
        @Override public void cleanupExpired() { findExpiredEntries(); }
        private void findExpiredEntries() {
            entries.entrySet().removeIf(item -> {
                if (item.getValue().expiresAt().isAfter(LocalDateTime.now())) return false;
                expired.incrementAndGet();
                return true;
            });
        }
        void seed(Entry entry) { entries.put(entry.cacheKey(), entry); }
        int size() { return entries.size(); }
        long expiredCount() { return expired.get(); }
        private record Lease(String token, LocalDateTime expiresAt) {}
    }
}
