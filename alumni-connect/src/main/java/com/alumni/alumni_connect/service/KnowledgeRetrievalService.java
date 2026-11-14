package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionRequest;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.security.CurrentUserService;
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
    private final KnowledgeContributionService contributions;
    private final CurrentUserService currentUser;

    public KnowledgeRetrievalService(KnowledgeContributionService contributions, CurrentUserService currentUser) {
        this.contributions = contributions;
        this.currentUser = currentUser;
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
        List<Scored> ranked = candidates.stream()
                .filter(c -> category == null || category.name().equals(c.category()))
                .filter(c -> matches(c.companyName(), company))
                .filter(c -> matches(c.jobRole(), role))
                .filter(c -> skill == null || c.entries().stream().anyMatch(entry ->
                        "TECHNOLOGY".equals(entry.type()) && contains(entry.value(), skill)))
                .filter(c -> topic == null || contains(c.narrative(), topic) || c.entries().stream().anyMatch(entry ->
                        Set.of("INTERVIEW_TOPIC", "INTERVIEW_QUESTION", "INTERVIEW_ROUND", "CHALLENGE", "LESSON")
                                .contains(entry.type()) && (contains(entry.value(), topic) || contains(entry.detail(), topic))))
                .map(c -> score(c, terms))
                .filter(s -> s.score() > 0)
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
        for (var c : contributions.recheckAiEligible(refs)) fresh.put(key(c.id(), c.version()), c);
        List<KnowledgeRetrievalResponse.Source> sources = selected.stream()
                .map(s -> fresh.get(key(s.value().id(), s.value().version())))
                .filter(Objects::nonNull)
                .map(c -> source(c, terms))
                .toList();
        // Fail closed if an eligibility change removed a selected source.
        boolean stillHasMore = hasMore && sources.size() == selected.size();
        return new KnowledgeRetrievalResponse(rawQuery.trim(), safePage, size, stillHasMore, sources);
    }

    private Scored score(KnowledgeContributionResponse c, List<String> terms) {
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
        double max = terms.size() * 9.0;
        return new Scored(c, Math.min(1.0, points / max), List.copyOf(matched));
    }

    private KnowledgeRetrievalResponse.Source source(KnowledgeContributionResponse c, List<String> terms) {
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
        Scored scored = score(c, terms);
        return new KnowledgeRetrievalResponse.Source(c.id(), c.version(), c.category(), KnowledgeContentSanitizer.redact(c.title()), excerpt,
                Math.round(scored.score() * 1000.0) / 1000.0, scored.fields(), "Alumni-reported contribution; not independently verified",
                KnowledgeContentSanitizer.redact(safe(c.companyName())), KnowledgeContentSanitizer.redact(safe(c.jobRole())));
    }

    private static List<String> terms(String input) {
        var matcher = TOKEN.matcher(norm(input));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        while (matcher.find()) if (!STOP_WORDS.contains(matcher.group())) result.add(matcher.group());
        return result.stream().limit(20).toList();
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
    private record Scored(KnowledgeContributionResponse value, double score, List<String> fields) {}
}
