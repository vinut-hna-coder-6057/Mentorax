package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.entity.UserSkill;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.repository.UserSkillRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import jakarta.annotation.PostConstruct;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MentorDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(MentorDiscoveryService.class);
    private static final int MAX_RANKING_SKILLS_PER_CANDIDATE = 50;
    private static final int APPROVED_ALUMNI_PAGE_SIZE = 500;
    private static final int MAX_RECOMMENDATIONS = 10;
    private static final int MAX_RANKING_PAGES_PER_REQUEST = 2;
    private static final int MAX_BATCH_BODY_BYTES = 7 * 1024 * 1024;
    private static final ObjectMapper REQUEST_SIZE_MAPPER = new ObjectMapper();
    private final UserRepository users;
    private final UserSkillRepository userSkills;
    private final CurrentUserService currentUser;
    private final RestTemplate restTemplate;
    @Value("${app.ai.enabled:false}") private boolean aiEnabled;
    @Value("${app.ai.service-url:http://127.0.0.1:8090}") private String aiServiceUrl;
    @Value("${app.ai.minimum-relevance:0.0}") private double minimumRelevance;
    @Value("${app.ai.minimum-semantic-relevance:0.72}") private double minimumSemanticRelevance = 0.72;
    @Value("${app.ai.ranking-strategy:tfidf}") private String rankingStrategy = "tfidf";

    public MentorDiscoveryService(UserRepository users, UserSkillRepository userSkills,
                                  CurrentUserService currentUser, RestTemplate restTemplate) {
        this.users = users; this.userSkills = userSkills; this.currentUser = currentUser;
        this.restTemplate = restTemplate;
    }

    @PostConstruct
    void validateMinimumRelevance() {
        if (!Double.isFinite(minimumRelevance) || minimumRelevance < 0 || minimumRelevance > 1) {
            throw new IllegalStateException("app.ai.minimum-relevance must be between 0.0 and 1.0");
        }
        if (!Double.isFinite(minimumSemanticRelevance) || minimumSemanticRelevance < 0
                || minimumSemanticRelevance > 1) {
            throw new IllegalStateException("app.ai.minimum-semantic-relevance must be between 0.0 and 1.0");
        }
        rankingStrategy = rankingStrategy.toLowerCase(Locale.ROOT);
        if (!Set.of("tfidf", "semantic", "hybrid").contains(rankingStrategy)) {
            throw new IllegalStateException("app.ai.ranking-strategy must be tfidf, semantic, or hybrid");
        }
    }

    @Transactional(readOnly = true)
    public MentorDiscoveryResponse discover(String goal) {
        User student = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(student.getRole())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Student access required");
        }
        DiscoveryAccumulator accumulation = new DiscoveryAccumulator(aiEnabled);
        List<PageWork> pendingPages = new ArrayList<>(MAX_RANKING_PAGES_PER_REQUEST);
        boolean approvedFound = false;
        int pagesRead = 0;
        long lastAlumniId = 0L;

        for (;;) {
            List<User> approved = users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc(
                    "ALUMNI", "APPROVED", lastAlumniId);
            if (approved.isEmpty()) break;
            approvedFound = true;
            pagesRead++;
            lastAlumniId = approved.getLast().getId();

            Map<Long, LinkedHashSet<String>> normalized = new HashMap<>();
            List<Long> approvedIds = approved.stream().map(User::getId).toList();
            for (UserSkill skill : userSkills.findByUser_IdIn(approvedIds)) {
                if (skill.getSkill() != null && skill.getSkill().getName() != null) {
                    normalized.computeIfAbsent(skill.getUser().getId(), ignored -> new LinkedHashSet<>())
                            .add(skill.getSkill().getName().trim());
                }
            }
            List<MentorCandidate> candidates = approved.stream().map(user -> {
                LinkedHashSet<String> skills = normalized.getOrDefault(user.getId(), new LinkedHashSet<>());
                if (user.getSkills() != null) Arrays.stream(user.getSkills().split("[,;|]"))
                        .map(String::trim).filter(s -> !s.isBlank()).forEach(skills::add);
                return new MentorCandidate(user.getId(), user.getName(), user.getJobRole(), user.getCompany(),
                        user.getBio(), List.copyOf(skills), user.getInterests());
            }).toList();

            boolean semanticRetrieval = aiEnabled && !"tfidf".equals(rankingStrategy);
            List<MentorCandidate> pythonCandidates = semanticRetrieval ? candidates : candidates.stream()
                    .filter(candidate -> hasEvidence(goal, candidate)).toList();
            pendingPages.add(new PageWork(candidates, pythonCandidates, pagesRead));
            if (pendingPages.size() == MAX_RANKING_PAGES_PER_REQUEST) {
                accumulation.process(goal, pendingPages);
                pendingPages.clear();
            }
            if (approved.size() < APPROVED_ALUMNI_PAGE_SIZE) break;
        }
        if (!pendingPages.isEmpty()) accumulation.process(goal, pendingPages);
        log.debug("Mentor discovery scanned {} approved-alumni page(s) in {} keyset query/queries",
                pagesRead, pagesRead + 1);

        if (!approvedFound) {
            return new MentorDiscoveryResponse(List.of(), "No approved alumni available", false,
                    QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null,
                    "There are currently no approved alumni profiles available to search.", List.of());
        }
        if (!accumulation.relevantFound) {
            return new MentorDiscoveryResponse(List.of(),
                    "Local profile evidence gate; no candidate met the configured threshold", false,
                    QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null,
                    "Approved alumni exist, but none had enough matching professional-profile evidence. Try a more specific career question.",
                    List.of());
        }

        boolean multiPage = pagesRead > 1;
        boolean crossPageSemantic = multiPage && aiEnabled && accumulation.pythonAvailable
                && accumulation.allPythonPagesSucceeded && "semantic".equals(rankingStrategy);
        boolean pythonOrdering = !multiPage && accumulation.firstPageUsedPython;
        boolean fallback = !pythonOrdering && !crossPageSemantic;
        List<CandidateRanking> selected;
        if (pythonOrdering) {
            selected = accumulation.firstPageRankings.stream()
                    .sorted(Comparator.comparingInt(CandidateRanking::pythonPosition))
                    .limit(MAX_RECOMMENDATIONS).toList();
        } else if (crossPageSemantic) {
            selected = accumulation.topBySemanticScore.stream().sorted(SEMANTIC_ORDER)
                    .limit(MAX_RECOMMENDATIONS).toList();
        } else {
            selected = accumulation.topByEvidence.stream().sorted(EVIDENCE_ORDER)
                    .limit(MAX_RECOMMENDATIONS).toList();
        }
        List<MentorRecommendation> results = selected.stream().map(ranked ->
                recommendation(goal, ranked.candidate(), ranked.evidenceScore(), ranked.semanticAccepted())).toList();
        String method = fallback ? (multiPage && aiEnabled
                ? "Java global profile-evidence ordering across approved alumni pages"
                : "Java keyword overlap with profile evidence gate")
                : multiPage ? "Sentence embedding ordering across approved alumni pages with Java evidence gate"
                : switch (rankingStrategy) {
                    case "semantic" -> "Sentence embedding ordering with Java profile evidence gate";
                    case "hybrid" -> "Hybrid rank-percentile ordering with Java profile evidence gate";
                    default -> "TF-IDF ordering with Java profile evidence gate";
                };
        return new MentorDiscoveryResponse(results, method, fallback,
                QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null,
                results.isEmpty() ? "No approved alumni met the configured relevance threshold." : null,
                List.of());
    }

    private boolean hasEvidence(String goal, MentorCandidate candidate) {
        double relevance = localScore(goal, candidate);
        return relevance > 0 && relevance >= minimumRelevance;
    }

    private boolean hasSubstantiveProfileEvidence(MentorCandidate candidate) {
        return (candidate.jobTitle() != null && !candidate.jobTitle().isBlank())
                || !candidate.skills().isEmpty()
                || (candidate.interests() != null && !candidate.interests().isBlank())
                || (candidate.bio() != null && !candidate.bio().isBlank());
    }

    private List<PythonRanking> rankBatchWithPython(String goal, List<PageWork> pages) {
        List<PythonRanking> rankings = new ArrayList<>(Collections.nCopies(pages.size(), null));
        List<Integer> pendingIndexes = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            if (!pages.get(index).pythonCandidates().isEmpty()) pendingIndexes.add(index);
        }
        int requestCount = 0;
        try {
            int cursor = 0;
            while (cursor < pendingIndexes.size()) {
                List<Integer> batchIndexes = new ArrayList<>();
                Map<String, Object> batchPayload;
                while (cursor < pendingIndexes.size() && batchIndexes.size() < MAX_RANKING_PAGES_PER_REQUEST) {
                    int candidateIndex = pendingIndexes.get(cursor);
                    batchIndexes.add(candidateIndex);
                    batchPayload = batchPayload(goal, pages, batchIndexes);
                    if (jsonSize(batchPayload) > MAX_BATCH_BODY_BYTES && batchIndexes.size() > 1) {
                        batchIndexes.removeLast();
                        break;
                    }
                    cursor++;
                }
                if (batchIndexes.isEmpty()) { // A single large page retains the existing /rank limit.
                    batchIndexes.add(pendingIndexes.get(cursor++));
                }
                requestCount++;
                if (batchIndexes.size() == 1) {
                    int pageIndex = batchIndexes.getFirst();
                    Map<String, Object> request = singlePayload(goal, pages.get(pageIndex).pythonCandidates());
                    rankings.set(pageIndex, validatePythonRanking(postRank("/rank", request),
                            pages.get(pageIndex).pythonCandidates()));
                } else {
                    Map<String, Object> response = postRank("/rank/batch", batchPayload(goal, pages, batchIndexes));
                    Object rawResults = response == null ? null : response.get("results");
                    if (rawResults instanceof List<?> results && results.size() == batchIndexes.size()) {
                        for (int index = 0; index < batchIndexes.size(); index++) {
                            Object item = results.get(index);
                            if (item instanceof Map<?, ?> result && result.get("result") instanceof Map<?, ?> body) {
                                rankings.set(batchIndexes.get(index), validatePythonRanking(stringKeyed(body),
                                        pages.get(batchIndexes.get(index)).pythonCandidates()));
                            }
                        }
                    } else {
                        log.info("Optional mentor ranking batch response rejected (invalid result list)");
                    }
                }
            }
        } catch (RuntimeException failure) {
            log.info("Optional mentor ranking service unavailable ({}); using local ranking",
                    failure.getClass().getSimpleName());
            return new ArrayList<>(Collections.nCopies(pages.size(), null));
        }
        log.debug("Mentor ranking sent {} Python request(s) for {} database page(s)",
                requestCount, pages.size());
        return rankings;
    }

    private Map<String, Object> batchPayload(String goal, List<PageWork> pages, List<Integer> indexes) {
        List<Map<String, Object>> requests = indexes.stream().map(index ->
                singlePayload(goal, pages.get(index).pythonCandidates())).toList();
        return Map.of("requests", requests);
    }

    private Map<String, Object> singlePayload(String goal, List<MentorCandidate> candidates) {
        List<Map<String, Object>> rankingCandidates = candidates.stream().map(candidate -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", candidate.id());
            fields.put("jobTitle", candidate.jobTitle());
            fields.put("company", candidate.company());
            fields.put("bio", candidate.bio());
            fields.put("skills", candidate.skills().stream().limit(MAX_RANKING_SKILLS_PER_CANDIDATE).toList());
            fields.put("interests", candidate.interests());
            return fields;
        }).toList();
        return Map.of("goal", goal, "candidates", rankingCandidates, "strategy", rankingStrategy);
    }

    private int jsonSize(Map<String, Object> payload) {
        try {
            return REQUEST_SIZE_MAPPER.writeValueAsBytes(payload).length;
        } catch (Exception serializationFailure) {
            throw new IllegalStateException("Could not size bounded ranking request", serializationFailure);
        }
    }

    private Map<String, Object> postRank(String path, Map<String, Object> payload) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                aiServiceUrl.replaceAll("/$", "") + path, HttpMethod.POST,
                new HttpEntity<>(payload, jsonHeaders()), new ParameterizedTypeReference<>() {});
        return response.getBody();
    }

    private Map<String, Object> stringKeyed(Map<?, ?> values) {
        Map<String, Object> result = new HashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private PythonRanking validatePythonRanking(Map<String, Object> responseBody,
                                                List<MentorCandidate> candidates) {
        Object raw = responseBody == null ? null : responseBody.get("rankedIds");
        if (!(raw instanceof List<?> ids)) return null;
        List<Long> validated = ids.stream().map(this::parseRankingId).filter(Objects::nonNull).distinct().toList();
        Set<Long> expectedIds = candidates.stream().map(MentorCandidate::id).collect(Collectors.toSet());
        Map<Long, Double> validatedScores = parseScores(responseBody.get("scores"), expectedIds);
        Map<Long, Double> semanticScores = Set.of("semantic", "hybrid").contains(rankingStrategy)
                ? parseScores(responseBody.get("semanticScores"), expectedIds) : Map.of();
        List<Long> scoreOrder = validatedScores == null ? List.of() : expectedIds.stream()
                .sorted(Comparator.comparingDouble((Long id) -> validatedScores.get(id)).reversed()
                        .thenComparingLong(Long::longValue)).toList();
        if (ids.size() != expectedIds.size() || validated.size() != expectedIds.size()
                || !new HashSet<>(validated).equals(expectedIds) || validatedScores == null
                || !validated.equals(scoreOrder)
                || (Set.of("semantic", "hybrid").contains(rankingStrategy) && semanticScores == null)) {
            log.info("Optional mentor ranking response rejected (count={}, expected={}, finalScores={}, order={}, semanticScores={})",
                    ids.size(), expectedIds.size(), validatedScores != null, validated.equals(scoreOrder),
                    semanticScores != null);
            return null;
        }
        return new PythonRanking(validated, validatedScores, semanticScores);
    }

    private void retainTop(List<CandidateRanking> top, CandidateRanking candidate,
                           Comparator<CandidateRanking> order) {
        top.add(candidate);
        top.sort(order);
        if (top.size() > MAX_RECOMMENDATIONS) top.remove(top.size() - 1);
    }

    private final class DiscoveryAccumulator {
        private final List<CandidateRanking> firstPageRankings = new ArrayList<>();
        private final List<CandidateRanking> topByEvidence = new ArrayList<>();
        private final List<CandidateRanking> topBySemanticScore = new ArrayList<>();
        private boolean relevantFound;
        private boolean pythonAvailable;
        private boolean allPythonPagesSucceeded;
        private boolean firstPageUsedPython;

        private DiscoveryAccumulator(boolean pythonAvailable) {
            this.pythonAvailable = pythonAvailable;
            this.allPythonPagesSucceeded = pythonAvailable;
        }

        private void process(String goal, List<PageWork> pages) {
            List<PythonRanking> rankings = pythonAvailable ? rankBatchWithPython(goal, pages)
                    : new ArrayList<>(Collections.nCopies(pages.size(), null));
            boolean semanticRetrieval = aiEnabled && !"tfidf".equals(rankingStrategy);
            for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
                PageWork page = pages.get(pageIndex);
                PythonRanking pythonRanking = rankings.get(pageIndex);
                if (!page.pythonCandidates().isEmpty() && pythonAvailable && pythonRanking == null) {
                    pythonAvailable = false;
                    allPythonPagesSucceeded = false;
                }
                if (semanticRetrieval && pythonRanking == null) allPythonPagesSucceeded = false;
                Map<Long, Integer> positions = new HashMap<>();
                if (pythonRanking != null) {
                    for (int index = 0; index < pythonRanking.rankedIds().size(); index++) {
                        positions.put(pythonRanking.rankedIds().get(index), index);
                    }
                }
                List<CandidateRanking> pageRankings = new ArrayList<>();
                for (MentorCandidate candidate : page.candidates()) {
                    double evidenceScore = localScore(goal, candidate);
                    Double semanticScore = pythonRanking == null ? null
                            : pythonRanking.semanticScores().get(candidate.id());
                    boolean lexicalAccepted = evidenceScore > 0 && evidenceScore >= minimumRelevance;
                    boolean semanticAccepted = semanticRetrieval && semanticScore != null
                            && semanticScore >= minimumSemanticRelevance
                            && hasSubstantiveProfileEvidence(candidate);
                    if (!lexicalAccepted && !semanticAccepted) continue;
                    relevantFound = true;
                    CandidateRanking ranked = new CandidateRanking(candidate, evidenceScore,
                            positions.getOrDefault(candidate.id(), Integer.MAX_VALUE), semanticScore,
                            semanticAccepted);
                    pageRankings.add(ranked);
                    retainTop(topByEvidence, ranked, EVIDENCE_ORDER);
                    if (semanticScore != null) retainTop(topBySemanticScore, ranked, SEMANTIC_ORDER);
                }
                if (page.pageNumber() == 1) {
                    firstPageRankings.addAll(pageRankings);
                    firstPageUsedPython = pythonRanking != null;
                } else {
                    firstPageRankings.clear();
                }
            }
        }
    }

    private static final Comparator<CandidateRanking> EVIDENCE_ORDER = Comparator
            .comparingDouble(CandidateRanking::evidenceScore).reversed()
            .thenComparingInt(CandidateRanking::pythonPosition)
            .thenComparing(ranked -> ranked.candidate().id());
    private static final Comparator<CandidateRanking> SEMANTIC_ORDER = Comparator
            .comparingDouble((CandidateRanking ranked) -> ranked.semanticScore() == null
                    ? -1.0 : ranked.semanticScore()).reversed()
            .thenComparing(ranked -> ranked.candidate().id());

    private record CandidateRanking(MentorCandidate candidate, double evidenceScore,
                                    int pythonPosition, Double semanticScore, boolean semanticAccepted) {}
    private record PageWork(List<MentorCandidate> candidates, List<MentorCandidate> pythonCandidates,
                            int pageNumber) {}
    private record PythonRanking(List<Long> rankedIds, Map<Long, Double> scores,
                                 Map<Long, Double> semanticScores) {}

    private MentorRecommendation recommendation(String goal, MentorCandidate c, double score,
                                                boolean semanticAccepted) {
        Set<String> terms = terms(goal);
        List<String> evidence = new ArrayList<>();
        c.skills().stream().filter(skill -> matches(terms, skill))
                .forEach(skill -> evidence.add("Skill: " + skill));
        if (matches(terms, c.jobTitle())) evidence.add("Current role: " + c.jobTitle());
        if (matches(terms, c.interests())) evidence.add("Career interest: " + c.interests());
        if (matches(terms, c.bio())) evidence.add("Profile bio mentions a related term");
        if (evidence.isEmpty() && semanticAccepted) {
            if (c.jobTitle() != null && !c.jobTitle().isBlank()) evidence.add("Current role: " + c.jobTitle());
            c.skills().stream().filter(skill -> !skill.isBlank()).limit(3)
                    .forEach(skill -> evidence.add("Profile skill: " + skill));
            if (evidence.isEmpty() && c.interests() != null && !c.interests().isBlank()) {
                evidence.add("Career interest: " + c.interests());
            }
            if (evidence.isEmpty() && c.bio() != null && !c.bio().isBlank()) {
                evidence.add("Professional profile bio provided");
            }
        }
        String firstName = c.name() == null || c.name().isBlank() ? "there" : c.name().trim().split("\\s+")[0];
        String topic = evidence.isEmpty() ? "Ask about their current role and the path into this field."
                : evidence.get(0).startsWith("Profile bio")
                ? "Ask how their work relates to your interest and what path led them there."
                : "Ask how their experience with " + evidence.get(0).replaceFirst("^[^:]+: ", "")
                        + " shaped their career path.";
        String title = c.jobTitle() == null || c.jobTitle().isBlank() ? "your career" : c.jobTitle();
        String draft = "Hi " + firstName + ", I’m exploring " + goal.trim() + " and noticed your experience as " + title + ". Would you be open to sharing a little about your path and advice for someone getting started?";
        return new MentorRecommendation(c.id(), c.name(), c.jobTitle(), c.company(), c.skills(),
                Math.round(score * 1000.0) / 10.0, evidence,
                semanticAccepted
                        ? "Semantic similarity is a retrieval signal, not a probability or proof of expertise. Verify the profile details; availability and mentoring preference are unknown."
                        : evidence.isEmpty() ? "No direct profile evidence matched the entered goal; review this profile before reaching out." : "Based only on public professional profile fields; availability and mentoring preference are unknown.",
                topic, draft);
    }

    private static final Set<String> NON_DISCRIMINATING_TERMS = Set.of(
            "how", "what", "when", "where", "which", "who", "why", "should", "could",
            "would", "can", "you", "your", "i", "me", "my", "we", "our",
            "the", "and", "for", "from", "into", "with", "about", "that", "this",
            "are", "was", "were", "have", "has", "had", "do", "does", "did", "to",
            "a", "an", "of", "in", "on", "at", "by", "as", "be", "is", "it",
            "learn", "learning", "prepare", "preparing", "improve", "improving", "become",
            "preparation", "prep", "career", "job", "work", "role", "professional",
            "skills", "skill", "computing", "development", "developer", "engineer",
            "engineering", "software", "technical", "question", "questions", "help",
            "guide", "someone", "struggle", "combine", "start", "started", "build");

    private double localScore(String goal, MentorCandidate c) {
        Set<String> terms = terms(goal);
        if (terms.isEmpty()) return 0;
        String directEvidence = String.join(" ", c.skills()) + " "
                + Objects.toString(c.jobTitle(), "") + " " + Objects.toString(c.interests(), "");
        Set<String> directTerms = terms(directEvidence);
        long directOverlap = terms.stream().filter(directTerms::contains).count();
        Set<String> bioTerms = terms(Objects.toString(c.bio(), ""));
        long supportingOverlap = terms.stream().filter(bioTerms::contains).count();
        if (directOverlap == 0 && supportingOverlap == 0) return 0;
        return Math.min(1.0, (directOverlap + supportingOverlap * 0.2) / terms.size());
    }
    private Map<Long, Double> parseScores(Object payload, Set<Long> expectedIds) {
        if (!(payload instanceof Map<?, ?> scores) || scores.size() != expectedIds.size()) return null;
        Map<Long, Double> parsed = new HashMap<>();
        for (Map.Entry<?, ?> entry : scores.entrySet()) {
            try {
                long candidateId = Long.parseLong(String.valueOf(entry.getKey()));
                Object rawScore = entry.getValue();
                if (!(rawScore instanceof Number number)) return null;
                double score = number.doubleValue();
                if (!expectedIds.contains(candidateId) || !Double.isFinite(score) || score < 0 || score > 1) {
                    return null;
                }
                parsed.put(candidateId, score);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return parsed.size() == expectedIds.size() ? parsed : null;
    }
    private boolean matches(Set<String> terms, String value) { return value != null && terms.stream().anyMatch(terms(value)::contains); }
    private Set<String> terms(String value) {
        String expanded = normalize(value)
                .replaceAll("\\b(softwear|softare)\\b", "software")
                .replaceAll("\\b(enginer|engineerng)\\b", "engineer")
                .replaceAll("\\b(interveiw|intreview)\\b", "interview")
                .replaceAll("\\bdeep learning\\b", "machinelearning")
                .replaceAll("\\b(artificial intelligence|machine learning|\\bml\\b|\\bai\\b)\\b", "machinelearning")
                .replaceAll("\\b(data structures and algorithms|dsa|algorithms?)\\b", "algorithm")
                .replaceAll("\\b(coding rounds?|coding interviews?)\\b", "codinginterview")
                .replaceAll("\\b(problem solving)\\b", "algorithm")
                .replaceAll("\\b(online assessments?|oas?)\\b", "assessment")
                .replaceAll("\\bsoftware engineer(?:ing)?\\b", "softwareengineering")
                .replaceAll("\\bdata engineer(?:ing)?\\b", "dataengineering")
                .replaceAll("\\b(full stack|fullstack)\\b", "fullstack")
                .replaceAll("\\b(system design)\\b", "systemdesign")
                .replaceAll("\\b(cloud computing)\\b", "cloud")
                .replaceAll("\\b(back end|backend)\\b", "backend")
                .replaceAll("\\bprep\\b", "preparation");
        return Arrays.stream(expanded.split("\\s+"))
                .filter(term -> term.length() > 2 && !NON_DISCRIMINATING_TERMS.contains(term))
                .map(this::canonicalTerm)
                .collect(Collectors.toSet());
    }
    private String canonicalTerm(String term) {
        if (term.endsWith("engineering")) return term.substring(0, term.length() - 3);
        if (term.endsWith("ies") && term.length() > 4) return term.substring(0, term.length() - 3) + "y";
        if (term.endsWith("s") && term.length() > 4) return term.substring(0, term.length() - 1);
        return term;
    }
    private Long parseRankingId(Object value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        return null;
    }
    private String normalize(String value) { return Normalizer.normalize(Objects.toString(value, "").toLowerCase(Locale.ROOT), Normalizer.Form.NFKC).replaceAll("[^\\p{L}\\p{N}]+", " ").trim(); }
    private HttpHeaders jsonHeaders() { HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); return headers; }
}
