package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionRequest;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class KnowledgeRetrievalService {
    private static final int MAX_QUERY = 500;
    private static final int MAX_CANDIDATES = 250;
    private static final int MAX_PAGE_SIZE = 10;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}+#.]{2,}");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "how", "what", "from",
            "about", "can", "could", "would", "should", "does", "did", "are", "was", "were", "you", "your",
            "into", "this", "that", "have", "has", "will", "when", "where", "who", "why", "a", "an", "to", "of", "in", "on", "is", "it");
    private static final List<Set<String>> SYNONYM_GROUPS = List.of(
            Set.of("sde", "swe", "software engineer", "software developer", "software engineering"),
            Set.of("intern", "internship", "summer intern", "summer internship"),
            Set.of("coding round", "programming round", "technical assessment", "online assessment", "oa"),
            Set.of("referral", "refer", "employee referral"),
            Set.of("backend", "back end", "server side", "api development"),
            Set.of("frontend", "front end", "client side", "ui development"),
            Set.of("full stack", "fullstack"),
            Set.of("dsa", "data structures", "algorithms", "data structure and algorithm"));
    private final KnowledgeContributionService contributions;
    private final CurrentUserService currentUser;
    private final KnowledgeSemanticRanker semanticRanker;
    private final double minimumSemanticScore;

    public KnowledgeRetrievalService(KnowledgeContributionService contributions, CurrentUserService currentUser) {
        this(contributions, currentUser, null, 0.48);
    }

    @Autowired
    public KnowledgeRetrievalService(KnowledgeContributionService contributions, CurrentUserService currentUser,
            KnowledgeSemanticRanker semanticRanker,
            @Value("${app.knowledge.retrieval.minimum-semantic-score:0.48}") double minimumSemanticScore) {
        if (!Double.isFinite(minimumSemanticScore) || minimumSemanticScore < 0 || minimumSemanticScore > 1)
            throw new IllegalArgumentException("Knowledge semantic score threshold must be between 0 and 1");
        this.contributions = contributions;
        this.currentUser = currentUser;
        this.semanticRanker = semanticRanker;
        this.minimumSemanticScore = minimumSemanticScore;
    }

    @Transactional(readOnly = true)
    public KnowledgeRetrievalResponse retrieve(String rawQuery, KnowledgeContributionRequest.Category category,
                                                int page, int requestedSize) {
        return retrieve(rawQuery, category, null, null, null, null, page, requestedSize);
    }

    @Transactional(readOnly = true)
    public KnowledgeRetrievalResponse retrieve(String rawQuery, KnowledgeContributionRequest.Category category,
            String company, String role, String skill, String topic, int page, int requestedSize) {
        User user = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(user.getRole()) || !"APPROVED".equalsIgnoreCase(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved students only");
        if (rawQuery == null || rawQuery.isBlank() || rawQuery.length() > MAX_QUERY)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query must contain at most 500 characters");
        validateFilter(company, "company");
        validateFilter(role, "role");
        validateFilter(skill, "skill");
        validateFilter(topic, "topic");
        int size = Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        List<String> terms = terms(rawQuery);
        if (terms.isEmpty()) return new KnowledgeRetrievalResponse(rawQuery.trim(), safePage, size, false, List.of());

        List<KnowledgeContributionResponse> candidates = new ArrayList<>();
        for (int p = 0; p < MAX_CANDIDATES / 50 && candidates.size() < MAX_CANDIDATES; p++) {
            List<KnowledgeContributionResponse> next = contributions.listAiEligible(p, 50);
            candidates.addAll(next);
            if (next.size() < 50) break;
        }
        List<KnowledgeContributionResponse> filtered = candidates.stream()
                .filter(c -> matchesFilters(c, category, company, role, skill, topic)).toList();
        Map<Long, Double> semanticScores = semanticRanker == null ? Map.of()
                : semanticRanker.scores(rawQuery, filtered.stream().map(c -> source(c, terms, 0.0, List.of())).toList());
        List<Scored> ranked = filtered.stream()
                .map(c -> score(c, terms, rawQuery, semanticScores.get(c.id()), !semanticScores.isEmpty()))
                .filter(s -> s.score() >= 0.08
                        || (s.semanticScore() != null && s.semanticScore() >= minimumSemanticScore))
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(s -> s.value().title(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(s -> s.value().id()))
                .toList();
        int from = (int) Math.min((long) safePage * size, ranked.size());
        int to = Math.min(from + size + 1, ranked.size());
        List<Scored> window = ranked.subList(from, to);
        boolean hasMore = window.size() > size;
        List<Scored> selected = window.subList(0, Math.min(size, window.size()));
        List<KnowledgeContributionService.ContributionVersionRef> refs = selected.stream()
                .map(s -> new KnowledgeContributionService.ContributionVersionRef(s.value().id(), s.value().version())).toList();
        Map<String, KnowledgeContributionResponse> fresh = new HashMap<>();
        if (!refs.isEmpty()) {
            for (var c : contributions.recheckAiEligible(refs)) fresh.put(key(c.id(), c.version()), c);
        }
        List<KnowledgeRetrievalResponse.Source> sources = selected.stream()
                .map(s -> {
                    KnowledgeContributionResponse current = fresh.get(key(s.value().id(), s.value().version()));
                    return current == null ? null : source(current, terms, s.score(), s.fields());
                })
                .filter(Objects::nonNull)
                .toList();
        // Fail closed if an eligibility change removed a selected source.
        boolean stillHasMore = hasMore && sources.size() == selected.size();
        return new KnowledgeRetrievalResponse(rawQuery.trim(), safePage, size, stillHasMore, sources);
    }

    private boolean matchesFilters(KnowledgeContributionResponse c, KnowledgeContributionRequest.Category category,
                                    String company, String role, String skill, String topic) {
        return (category == null || category.name().equals(c.category()))
                && matches(c.companyName(), company) && matches(c.jobRole(), role)
                && (skill == null || c.entries().stream().anyMatch(entry ->
                        "TECHNOLOGY".equals(entry.type()) && contains(entry.value(), skill)))
                && (topic == null || contains(c.narrative(), topic) || c.entries().stream().anyMatch(entry ->
                        Set.of("INTERVIEW_TOPIC", "INTERVIEW_QUESTION", "INTERVIEW_ROUND", "CHALLENGE", "LESSON")
                                .contains(entry.type()) && (contains(entry.value(), topic) || contains(entry.detail(), topic))));
    }

    private Scored score(KnowledgeContributionResponse c, List<String> terms, String query,
                         Double semanticScore, boolean semanticAvailable) {
        String title = norm(c.title());
        String context = norm(String.join(" ", Optional.ofNullable(c.companyName()).orElse(""),
                Optional.ofNullable(c.jobRole()).orElse(""), c.category()));
        String narrative = norm(c.narrative());
        String entries = norm(c.entries().stream().map(e -> String.join(" ", safe(e.value()), safe(e.detail()),
                safe(e.fromRole()), safe(e.toRole()))).reduce("", (a, b) -> a + " " + b));
        int points = 0;
        Set<String> matched = new LinkedHashSet<>();
        for (String term : terms) {
            if (title.contains(term)) { points += 4; matched.add("title"); }
            if (context.contains(term)) { points += 2; matched.add("category/company/role"); }
            if (entries.contains(term)) { points += 2; matched.add("structured details"); }
            if (narrative.contains(term)) { points += 1; matched.add("narrative"); }
        }
        double lexical = Math.min(1.0, points / (Math.max(1, terms.size()) * 9.0));
        String normalizedQuery = norm(query);
        if (c.companyName() != null && !c.companyName().isBlank()
                && normalizedQuery.contains(norm(c.companyName()))) {
            lexical = Math.min(1.0, lexical + 0.20);
            matched.add("exact company");
        }
        if (c.jobRole() != null && !c.jobRole().isBlank()
                && normalizedQuery.contains(norm(c.jobRole()))) {
            lexical = Math.min(1.0, lexical + 0.15);
            matched.add("exact role");
        }
        double fused = semanticAvailable && semanticScore != null
                ? 0.55 * lexical + 0.45 * semanticScore : lexical;
        return new Scored(c, fused, semanticScore, List.copyOf(matched));
    }

    private KnowledgeRetrievalResponse.Source source(KnowledgeContributionResponse c, List<String> terms,
                                                     double relevance, List<String> matchedFields) {
        String excerpt = c.narrative().strip();
        int cut = excerpt.indexOf('.');
        if (cut >= 80 && cut < 700) excerpt = excerpt.substring(0, cut + 1);
        List<String> details = c.entries().stream().filter(entry -> {
            String searchable = norm(String.join(" ", safe(entry.value()), safe(entry.detail()), safe(entry.fromRole()), safe(entry.toRole())));
            return terms.stream().anyMatch(searchable::contains);
        }).limit(3).map(entry -> entry.type() + ": " + safe(entry.value())
                + (entry.detail() == null ? "" : " — " + entry.detail())).toList();
        if (!details.isEmpty()) excerpt = excerpt + " Relevant structured details: " + String.join("; ", details) + ".";
        if (excerpt.length() > 700) excerpt = excerpt.substring(0, 697) + "...";
        excerpt = KnowledgeContentSanitizer.redact(excerpt);
        return new KnowledgeRetrievalResponse.Source(c.id(), c.version(), c.category(), KnowledgeContentSanitizer.redact(c.title()), excerpt,
                Math.round(relevance * 1000.0) / 1000.0, matchedFields, "Alumni-reported contribution; not independently verified",
                KnowledgeContentSanitizer.redact(safe(c.companyName())), KnowledgeContentSanitizer.redact(safe(c.jobRole())));
    }

    private static List<String> terms(String input) {
        var matcher = TOKEN.matcher(norm(input));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        while (matcher.find()) if (!STOP_WORDS.contains(matcher.group())) result.add(matcher.group());
        String normalized = norm(input);
        for (Set<String> group : SYNONYM_GROUPS) {
            if (group.stream().anyMatch(phrase -> normalized.contains(norm(phrase)))) {
                for (String phrase : group) {
                    var aliasMatcher = TOKEN.matcher(norm(phrase));
                    while (aliasMatcher.find()) if (!STOP_WORDS.contains(aliasMatcher.group()))
                        result.add(aliasMatcher.group());
                }
            }
        }
        return result.stream().limit(40).toList();
    }
    private static String norm(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static boolean matches(String value, String filter) {
        return filter == null || contains(value, filter);
    }
    private static boolean contains(String value, String filter) {
        return value != null && Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .contains(Normalizer.normalize(filter, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
    }
    private static void validateFilter(String value, String field) {
        if (value != null && (value.isBlank() || value.length() > 150))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " filter must contain 1 to 150 characters");
    }
    private static String key(long id, int version) { return id + ":" + version; }
    private record Scored(KnowledgeContributionResponse value, double score, Double semanticScore,
                          List<String> fields) {}
}
