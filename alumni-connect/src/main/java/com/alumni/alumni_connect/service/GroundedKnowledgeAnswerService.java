package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.dto.MentorRecommendation;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeAnswerResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

@Service
public class GroundedKnowledgeAnswerService {
    private static final Logger log = LoggerFactory.getLogger(GroundedKnowledgeAnswerService.class);
    private final KnowledgeRetrievalService retrieval;
    private final MentorDiscoveryService mentorDiscovery;
    private final KnowledgeContributionService contributions;
    private final List<GroundedAnswerProvider> providers;
    @Value("${app.knowledge.answer-provider:deterministic}") private String provider = "deterministic";

    public GroundedKnowledgeAnswerService(KnowledgeRetrievalService retrieval, MentorDiscoveryService mentorDiscovery,
                                          KnowledgeContributionService contributions,
                                          List<GroundedAnswerProvider> providers) {
        this.retrieval = retrieval;
        this.mentorDiscovery = mentorDiscovery;
        this.contributions = contributions;
        this.providers = providers;
    }

    public KnowledgeAnswerResponse answer(String question) {
        KnowledgeRetrievalResponse found = retrieval.retrieve(question, null, 0, 5);
        List<KnowledgeRetrievalResponse.Source> sources = found.sources().stream()
                .filter(source -> source.relevance() >= 0.08).toList();
        if (sources.isEmpty()) return insufficient(question, mode());
        GroundedAnswerProvider configured = providers.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase(provider)).findFirst().orElse(null);
        GroundedAnswerProvider fallback = providers.stream()
                .filter(candidate -> candidate.id().equalsIgnoreCase("deterministic")).findFirst().orElseThrow();
        String answerMode = configured == null ? "DETERMINISTIC_PROVIDER_UNAVAILABLE" : mode();
        for (int attempt = 0; attempt < 2 && !sources.isEmpty(); attempt++) {
            boolean conflict = materiallyConflicts(sources);
            String answer;
            if (configured == null) {
                answer = fallback.generate(question, sources, conflict);
            } else {
                try {
                    answer = configured.generate(question, sources, conflict);
                } catch (RuntimeException failure) {
                    // Never log the question, evidence, model response, or exception message.
                    log.warn("Grounded answer provider unavailable; falling back (provider={}, errorType={})",
                            configured.id(), failure.getClass().getSimpleName());
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
                return new KnowledgeAnswerResponse(answer, conflict ? "CONFLICTING_REPORTS" : "ALUMNI_REPORTED_EVIDENCE",
                        answerMode, reason, sources, conflict ? mentors(question) : List.of());
            }
            sources = fresh;
            answerMode = "DETERMINISTIC_PROVIDER_FALLBACK";
        }
        return insufficient(question, "DETERMINISTIC_PROVIDER_FALLBACK");
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
        return providers.stream().anyMatch(candidate -> candidate.id().equalsIgnoreCase(provider))
                ? ("deterministic".equalsIgnoreCase(provider) ? "DETERMINISTIC_GROUNDED" : "OLLAMA_GROUNDED")
                : "DETERMINISTIC_PROVIDER_UNAVAILABLE";
    }

    private KnowledgeAnswerResponse insufficient(String question, String answerMode) {
        String reason = "No current, consented alumni contribution provided enough relevant evidence.";
        return new KnowledgeAnswerResponse(
                "I don't have enough eligible alumni knowledge to answer that reliably. No alumni-reported facts were found for this question.",
                "INSUFFICIENT_EVIDENCE", answerMode, reason, List.of(), mentors(question));
    }
}
