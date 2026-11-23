package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.dto.MentorRecommendation;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeAnswerResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class GroundedKnowledgeAnswerService {
    private static final Logger log = LoggerFactory.getLogger(GroundedKnowledgeAnswerService.class);
    private final KnowledgeRetrievalService retrieval;
    private final MentorDiscoveryService mentorDiscovery;
    private final KnowledgeContributionService contributions;
    private final List<GroundedAnswerProvider> providers;
    private final KnowledgeAnswerCacheStore cache;
    private final KnowledgeAnswerCachePolicy cachePolicy;
    private final MeterRegistry meters;
    @Value("${app.knowledge.answer-provider:deterministic}") private String provider = "deterministic";
    @Value("${app.knowledge.cache.enabled:true}") private boolean cacheEnabled = true;
    @Value("${app.knowledge.cache.single-flight-wait-ms:22000}") private long cacheWaitMs = 22000;
    @Value("${app.knowledge.cache.lock-lease-seconds:60}") private long lockLeaseSeconds = 60;
    @Value("${app.knowledge.provider.timeout-ms:20000}") private long providerTimeoutMs = 20000;
    @Value("${app.knowledge.openai.model:gpt-4.1-mini}") private String openAiModel = "gpt-4.1-mini";
    @Value("${app.knowledge.ollama.model:gemma3:1b}") private String ollamaModel = "gemma3:1b";

    /** Compatibility constructor for focused service tests and non-Spring callers. */
    public GroundedKnowledgeAnswerService(KnowledgeRetrievalService retrieval, MentorDiscoveryService mentorDiscovery,
                                          KnowledgeContributionService contributions,
                                          List<GroundedAnswerProvider> providers) {
        this(retrieval, mentorDiscovery, contributions, providers, null, null, null);
    }

    @Autowired
    public GroundedKnowledgeAnswerService(KnowledgeRetrievalService retrieval, MentorDiscoveryService mentorDiscovery,
                                          KnowledgeContributionService contributions,
                                          List<GroundedAnswerProvider> providers,
                                          KnowledgeAnswerCacheStore cache,
                                          KnowledgeAnswerCachePolicy cachePolicy,
                                          MeterRegistry meters) {
        this.retrieval = retrieval;
        this.mentorDiscovery = mentorDiscovery;
        this.contributions = contributions;
        this.providers = providers;
        this.cache = cache;
        this.cachePolicy = cachePolicy;
        this.meters = meters;
    }

    public KnowledgeAnswerResponse answer(String question) {
        return answer(question, true);
    }

    /** Conversation calls pass false because their context contains private message history. */
    public KnowledgeAnswerResponse answer(String question, boolean allowSharedCache) {
        KnowledgeAnswerCachePolicy.Decision decision = null;
        if (allowSharedCache && cachePolicy != null) {
            try { decision = cachePolicy.decide(question); }
            catch (RuntimeException ignored) { /* Classification failures always bypass. */ }
        }
        if (!allowSharedCache || decision == null || !decision.eligible() || !cacheEnabled || cache == null) {
            metric("requests", "bypass", 1);
        }
        KnowledgeRetrievalResponse found = retrieval.retrieve(question, null, 0, 5);
        List<KnowledgeRetrievalResponse.Source> sources = found.sources().stream()
                .filter(source -> source.relevance() >= 0.08).toList();
        if (sources.isEmpty()) {
            if (decision != null && decision.eligible()) metric("requests", "bypass", 1);
            return insufficient(question, mode());
        }
        boolean conflict = materiallyConflicts(sources);
        if (!allowSharedCache || decision == null || !decision.eligible() || !cacheEnabled || cache == null || conflict) {
            if (conflict && decision != null && decision.eligible()) metric("requests", "bypass", 1);
            return generate(question, sources);
        }
        metric("requests", "eligible", 1);
        GroundedAnswerProvider configured = configuredProvider();
        String answerMode = mode();
        String modelId = configured == null ? "unknown" : modelId(configured);
        String key;
        try {
            key = cachePolicy.key(decision, sources,
                    configured == null ? answerMode : configured.id(), modelId);
        } catch (RuntimeException failure) {
            metric("requests", "error", 1);
            return generate(question, sources);
        }

        KnowledgeAnswerResponse hit;
        try { hit = validatedHit(key, sources, decision); }
        catch (RuntimeException failure) {
            metric("requests", "error", 1);
            log.warn("Shared knowledge answer cache lookup failed (errorType={})",
                    failure.getClass().getSimpleName());
            return generate(question, sources);
        }
        if (hit != null) return hit;
        String lockToken = UUID.randomUUID().toString();
        boolean acquired;
        try {
            acquired = cache.tryAcquire(key, lockToken, leaseDuration());
        } catch (RuntimeException failure) {
            metric("requests", "error", 1);
            return generate(question, sources);
        }
        if (!acquired) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(cacheWaitMs, 25000)));
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(75);
                    hit = validatedHit(key, sources, decision);
                    if (hit != null) return hit;
                    if (cache.tryAcquire(key, lockToken, leaseDuration())) {
                        acquired = true;
                        break;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException failure) {
                    metric("requests", "error", 1);
                    break;
                }
            }
            if (!acquired) {
                metric("requests", "miss", 1);
                metric("single_flight", "wait_timeout", 1);
                // Preserve availability after a bounded wait; this fallback is not cached.
                return generate(question, sources);
            }
        }
        try {
            try {
                hit = validatedHit(key, sources, decision);
                if (hit != null) return hit;
            } catch (RuntimeException failure) {
                metric("requests", "error", 1);
                log.warn("Shared knowledge answer cache lookup failed (errorType={})",
                        failure.getClass().getSimpleName());
                return generate(question, sources);
            }
            metric("requests", "miss", 1);
            KnowledgeAnswerResponse generated = generate(question, sources);
            try {
                if (cacheable(generated)) {
                List<KnowledgeContributionService.ContributionVersionRef> refs = sources.stream()
                        .map(source -> new KnowledgeContributionService.ContributionVersionRef(
                                source.contributionId(), source.version())).toList();
                Set<String> fresh = contributions.recheckFreshAiEligibleKeys(refs);
                if (fresh.containsAll(refKeys(refs))) {
                    KnowledgeAnswerCacheStore.Entry entry = new KnowledgeAnswerCacheStore.Entry(
                            key, KnowledgeAnswerCachePolicy.CACHE_SCHEMA_VERSION, decision.intent(),
                            cachePolicy.canonicalForm(decision, sources),
                            generated.answer(), generated.evidenceStatus(), generated.answerMode(),
                            configured.id(), modelId, KnowledgeAnswerCachePolicy.PROMPT_VERSION,
                            refs.stream().map(ref -> new KnowledgeAnswerCacheStore.SourceRef(ref.contributionId(), ref.version())).toList(),
                            LocalDateTime.now(ZoneOffset.UTC).plus(decision.ttl()));
                    if (cache.save(entry, lockToken)) metric("writes", "stored", 1);
                }
                }
            } catch (RuntimeException failure) {
                metric("requests", "error", 1);
                log.warn("Shared knowledge answer cache write failed (errorType={})",
                        failure.getClass().getSimpleName());
            }
            return generated;
        } finally {
            try { cache.release(key, lockToken); }
            catch (RuntimeException failure) {
                metric("requests", "error", 1);
                log.warn("Shared knowledge answer cache lease release failed (errorType={})",
                        failure.getClass().getSimpleName());
            }
        }
    }

    private KnowledgeAnswerResponse validatedHit(String key, List<KnowledgeRetrievalResponse.Source> sources,
                                                  KnowledgeAnswerCachePolicy.Decision decision) {
        long started = System.nanoTime();
        try {
        var entry = cache.find(key);
        if (entry.isEmpty()) return null;
        KnowledgeAnswerCacheStore.Entry cached = entry.get();
        Set<String> expected = sources.stream().map(source -> source.contributionId() + ":" + source.version())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> saved = cached.sources().stream().map(source -> source.contributionId() + ":" + source.version())
                .collect(java.util.stream.Collectors.toSet());
        GroundedAnswerProvider configured = configuredProvider();
        String currentProvider = configured == null ? "" : configured.id();
        String currentModel = configured == null ? "" : modelId(configured);
        if (expected.isEmpty() || !expected.equals(saved) || cached.answer() == null || cached.answer().isBlank()
                || !"ALUMNI_REPORTED_EVIDENCE".equals(cached.evidenceStatus())
                || !cacheableMode(cached.answerMode())
                || !validCachedCitations(cached.answer(), sources)
                || cached.cacheVersion() != KnowledgeAnswerCachePolicy.CACHE_SCHEMA_VERSION
                || !decision.intent().equals(cached.intent())
                || !cachePolicy.canonicalForm(decision, sources).equals(cached.canonicalQuestion())
                || !KnowledgeAnswerCachePolicy.PROMPT_VERSION.equals(cached.promptVersion())
                || !currentProvider.equalsIgnoreCase(cached.providerId())
                || !currentModel.equals(cached.modelId())) {
            cache.invalidate(key, "malformed_entry");
            metric("invalidations", "malformed_entry", 1);
            return null;
        }
        List<KnowledgeContributionService.ContributionVersionRef> refs = cached.sources().stream()
                .map(source -> new KnowledgeContributionService.ContributionVersionRef(
                        source.contributionId(), source.version())).toList();
        Set<String> fresh = contributions.recheckFreshAiEligibleKeys(refs);
        if (!fresh.containsAll(saved)) {
            cache.invalidate(key, fresh.isEmpty() ? "source_ineligible" : "source_not_fresh");
            metric("invalidations", fresh.isEmpty() ? "source_ineligible" : "source_not_fresh", 1);
            return null;
        }
        cache.recordHit(key);
        metric("requests", "hit", 1);
        metric("generations_avoided", "cache_hit", 1);
        if ("OPENAI_GROUNDED".equals(cached.answerMode()) || "OLLAMA_GROUNDED".equals(cached.answerMode())) {
            metric("ai_generations_avoided", cached.providerId(), 1);
            // Provider token telemetry is unavailable. Estimate completion tokens as answer characters / 4.
            metric("estimated_completion_tokens_saved", "estimate", Math.max(1, cached.answer().length() / 4));
        }
        return new KnowledgeAnswerResponse(cached.answer(), cached.evidenceStatus(), cached.answerMode(), null,
                sources, List.of());
        } finally {
            recordLatency("lookup", started);
        }
    }

    private KnowledgeAnswerResponse generate(String question, List<KnowledgeRetrievalResponse.Source> sources) {
        long started = System.nanoTime();
        metric("generations", "attempt", 1);
        GroundedAnswerProvider configured = providers.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(provider)).findFirst().orElse(null);
        GroundedAnswerProvider fallback = providers.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase("deterministic")).findFirst().orElseThrow();
        String answerMode = mode();
        for (int attempt = 0; attempt < 2 && !sources.isEmpty(); attempt++) {
            boolean conflict = materiallyConflicts(sources);
            String answer;
            if (configured == null || !configured.isConfigured()) {
                answer = fallback.generate(question, sources, conflict);
                answerMode = configured == null
                        ? "DETERMINISTIC_PROVIDER_UNAVAILABLE"
                        : "DETERMINISTIC_PROVIDER_MISCONFIGURED";
            } else {
                try {
                    metric("ai_generations", configured.id(), 1);
                    answer = configured.generate(question, sources, conflict);
                } catch (GroundedInsufficientEvidenceException insufficient) {
                    return insufficient(question, "OPENAI_INSUFFICIENT_EVIDENCE");
                } catch (RuntimeException failure) {
                    // Never log the question, evidence, model response, or exception message.
                    log.warn("Grounded answer provider unavailable; falling back (provider={}, errorType={})",
                            configured.id(), failure.getClass().getSimpleName());
                    metric("fallback_generations", "deterministic", 1);
                    answer = fallback.generate(question, sources, conflict);
                    answerMode = "DETERMINISTIC_PROVIDER_FALLBACK";
                }
            }
            Set<String> currentlyEligible = contributions.recheckAiEligible(sources.stream()
                    .map(source -> new KnowledgeContributionService.ContributionVersionRef(
                            source.contributionId(), source.version())).toList()).stream()
                    .map(source -> source.id() + ":" + source.version()).collect(java.util.stream.Collectors.toSet());
            List<KnowledgeRetrievalResponse.Source> fresh = sources.stream()
                    .filter(source -> currentlyEligible.contains(source.contributionId() + ":" + source.version())).toList();
            if (fresh.size() == sources.size()) {
                String reason = conflict ? "Eligible alumni reports disagree on a detail for the same company and role." : null;
                recordLatency("generation", started);
                return new KnowledgeAnswerResponse(answer, conflict ? "CONFLICTING_REPORTS" : "ALUMNI_REPORTED_EVIDENCE",
                        answerMode, reason, sources, conflict ? mentors(question) : List.of());
            }
            sources = fresh;
            answerMode = "DETERMINISTIC_PROVIDER_FALLBACK";
        }
        recordLatency("generation", started);
        return insufficient(question, "DETERMINISTIC_PROVIDER_FALLBACK");
    }

    private boolean cacheable(KnowledgeAnswerResponse answer) {
        return answer != null && "ALUMNI_REPORTED_EVIDENCE".equals(answer.evidenceStatus())
                && answer.answer() != null && !answer.answer().isBlank() && answer.answer().length() <= 8000
                && answer.sources() != null && !answer.sources().isEmpty()
                && (answer.mentors() == null || answer.mentors().isEmpty())
                && cacheableMode(answer.answerMode());
    }

    private Duration leaseDuration() {
        long providerBoundSeconds = (providerTimeoutMs + 999) / 1000 + 3;
        long seconds = Math.max(lockLeaseSeconds, providerBoundSeconds);
        return Duration.ofSeconds(Math.max(5, Math.min(120, seconds)));
    }

    private boolean validCachedCitations(String answer, List<KnowledgeRetrievalResponse.Source> sources) {
        try {
            return answer != null && answer.length() <= 8000
                    && answer.equals(GroundedAnswerValidation.validateExtractiveCitations(answer, sources, 8000));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean cacheableMode(String mode) {
        return "OPENAI_GROUNDED".equals(mode) || "OLLAMA_GROUNDED".equals(mode);
    }

    private GroundedAnswerProvider configuredProvider() {
        return providers.stream().filter(candidate -> candidate.id().equalsIgnoreCase(provider))
                .findFirst().filter(GroundedAnswerProvider::isConfigured).orElse(null);
    }

    private String modelId(GroundedAnswerProvider selected) {
        return switch (selected.id().toLowerCase(java.util.Locale.ROOT)) {
            case "openai" -> openAiModel;
            case "ollama" -> ollamaModel;
            default -> selected.id();
        };
    }

    private static Set<String> refKeys(List<KnowledgeContributionService.ContributionVersionRef> refs) {
        return refs.stream().map(ref -> ref.contributionId() + ":" + ref.version())
                .collect(java.util.stream.Collectors.toSet());
    }

    private void metric(String name, String outcome, long count) {
        if (meters == null) return;
        meters.counter("mentorax.knowledge.answer.cache." + name, "outcome", outcome).increment(count);
    }

    private void recordLatency(String operation, long startedNanos) {
        if (meters != null) meters.timer("mentorax.knowledge.answer.cache." + operation + ".latency")
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
    }

    private List<KnowledgeAnswerResponse.Mentor> mentors(String question) {
        MentorDiscoveryResponse result = mentorDiscovery.discover(question);
        return result.recommendations().stream().map(this::mentor).toList();
    }

    private KnowledgeAnswerResponse.Mentor mentor(MentorRecommendation m) {
        return new KnowledgeAnswerResponse.Mentor(m.id(), m.name(), m.jobTitle(), m.company(),
                m.relevanceEstimate(), m.uncertainty(), m.conversationTopic());
    }

    private boolean materiallyConflicts(List<KnowledgeRetrievalResponse.Source> sources) {
        List<String[]> opposites = List.of(new String[]{"remote", "onsite"}, new String[]{"remote", "on-site"},
                new String[]{"required", "optional"}, new String[]{"paid", "unpaid"});
        for (int i = 0; i < sources.size(); i++) for (int j = i + 1; j < sources.size(); j++) {
            var a = sources.get(i);
            var b = sources.get(j);
            if (a.category().equals(b.category()) && a.companyName() != null && !a.companyName().isBlank()
                    && a.companyName().equalsIgnoreCase(b.companyName()) && a.jobRole() != null
                    && a.jobRole().equalsIgnoreCase(b.jobRole())) {
                String left = a.excerpt().toLowerCase(java.util.Locale.ROOT);
                String right = b.excerpt().toLowerCase(java.util.Locale.ROOT);
                if (opposites.stream().anyMatch(pair -> left.contains(pair[0]) && right.contains(pair[1])
                        || left.contains(pair[1]) && right.contains(pair[0]))) return true;
            }
        }
        return false;
    }

    private String mode() {
        GroundedAnswerProvider selected = providers.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(provider)).findFirst().orElse(null);
        if (selected == null) return "DETERMINISTIC_PROVIDER_UNAVAILABLE";
        if (!selected.isConfigured()) return "DETERMINISTIC_PROVIDER_MISCONFIGURED";
        return switch (selected.id().toLowerCase(java.util.Locale.ROOT)) {
            case "deterministic" -> "DETERMINISTIC_GROUNDED";
            case "ollama" -> "OLLAMA_GROUNDED";
            case "openai" -> "OPENAI_GROUNDED";
            default -> "DETERMINISTIC_PROVIDER_UNAVAILABLE";
        };
    }

    private KnowledgeAnswerResponse insufficient(String question, String answerMode) {
        String reason = "No current, consented alumni contribution provided enough relevant evidence.";
        return new KnowledgeAnswerResponse(
                "I don't have enough eligible alumni knowledge to answer that reliably. No alumni-reported facts were found for this question.",
                "INSUFFICIENT_EVIDENCE", answerMode, reason, List.of(), mentors(question));
    }
}
