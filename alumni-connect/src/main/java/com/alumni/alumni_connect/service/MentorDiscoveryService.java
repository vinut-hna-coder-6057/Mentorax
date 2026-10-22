package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.entity.UserSkill;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.repository.UserSkillRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
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
import org.springframework.data.domain.PageRequest;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MentorDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(MentorDiscoveryService.class);
    private static final int MAX_RANKING_SKILLS_PER_CANDIDATE = 50;
    private final UserRepository users;
    private final UserSkillRepository userSkills;
    private final CurrentUserService currentUser;
    private final RestTemplate restTemplate;
    @Value("${app.ai.enabled:false}") private boolean aiEnabled;
    @Value("${app.ai.service-url:http://127.0.0.1:8090}") private String aiServiceUrl;
    @Value("${app.ai.minimum-relevance:0.0}") private double minimumRelevance;

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
    }

    @Transactional(readOnly = true)
    public MentorDiscoveryResponse discover(String goal) {
        User student = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(student.getRole())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Student access required");
        }
        List<User> approved = users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500));
        if (approved.isEmpty()) {
            return new MentorDiscoveryResponse(List.of(), "No approved alumni available", false,
                    QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null,
                    "There are currently no approved alumni profiles available to search.", List.of());
        }
        Map<Long, LinkedHashSet<String>> normalized = new HashMap<>();
        for (UserSkill skill : userSkills.findByUser_IdIn(approved.stream().map(User::getId).toList())) {
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

        List<MentorCandidate> relevantCandidates = candidates.stream()
                .filter(candidate -> {
                    double relevance = localScore(goal, candidate);
                    return relevance > 0 && relevance >= minimumRelevance;
                }).toList();
        if (relevantCandidates.isEmpty()) {
            return new MentorDiscoveryResponse(List.of(),
                    "Local profile evidence gate; no candidate met the configured threshold", false,
                    QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null,
                    "Approved alumni exist, but none had enough matching professional-profile evidence. Try a more specific career question.",
                    List.of());
        }

        List<Long> rankedIds = null;
        boolean fallback = true;
        if (aiEnabled) {
            try {
                // Names are not needed for ranking; keep personally identifying data
                // inside Spring, which owns the candidate records and explanations.
                List<Map<String, Object>> rankingCandidates = relevantCandidates.stream().map(candidate -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("id", candidate.id());
                    fields.put("jobTitle", candidate.jobTitle());
                    fields.put("company", candidate.company());
                    fields.put("bio", candidate.bio());
                    fields.put("skills", candidate.skills().stream()
                            .limit(MAX_RANKING_SKILLS_PER_CANDIDATE).toList());
                    fields.put("interests", candidate.interests());
                    return fields;
                }).toList();
                Map<String, Object> payload = Map.of("goal", goal, "candidates", rankingCandidates);
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        aiServiceUrl.replaceAll("/$", "") + "/rank", HttpMethod.POST,
                        new HttpEntity<>(payload, jsonHeaders()), new ParameterizedTypeReference<>() {});
                Map<String, Object> responseBody = response.getBody();
                Object raw = responseBody == null ? null : responseBody.get("rankedIds");
                if (raw instanceof List<?> ids) {
                    List<Long> validated = ids.stream().map(this::parseRankingId)
                            .filter(Objects::nonNull)
                            .filter(id -> relevantCandidates.stream().anyMatch(c -> c.id().equals(id)))
                            .distinct().toList();
                    Set<Long> expectedIds = relevantCandidates.stream().map(MentorCandidate::id)
                            .collect(Collectors.toSet());
                    Map<Long, Double> validatedScores = parseScores(
                            responseBody.get("scores"), expectedIds);
                    boolean completeRanking = ids.size() == expectedIds.size()
                            && validated.size() == expectedIds.size()
                            && new HashSet<>(validated).equals(expectedIds);
                    if (completeRanking && validatedScores != null) {
                        rankedIds = validated;
                        fallback = false;
                    } else {
                        log.info("Optional mentor ranking response rejected ({}); using local ranking",
                                !completeRanking ? "incomplete ranking" : "invalid score map");
                    }
                } else {
                    log.info("Optional mentor ranking response rejected ({}); using local ranking",
                            responseBody == null ? "empty response body" : "invalid rankedIds field");
                }
            } catch (RuntimeException failure) {
                log.info("Optional mentor ranking service unavailable ({}); using local ranking",
                        failure.getClass().getSimpleName());
            }
        }
        if (rankedIds == null) rankedIds = relevantCandidates.stream().sorted(Comparator
                .comparingDouble((MentorCandidate c) -> localScore(goal, c)).reversed()
                .thenComparing(MentorCandidate::id)).map(MentorCandidate::id).toList();

        Map<Long, MentorCandidate> byId = relevantCandidates.stream().collect(Collectors.toMap(MentorCandidate::id, c -> c));
        final boolean usedFallback = fallback;
        List<MentorRecommendation> results = rankedIds.stream().map(byId::get).filter(Objects::nonNull)
                .limit(10).map(candidate -> recommendation(goal, candidate,
                        localScore(goal, candidate))).toList();
        String method = fallback ? "Java keyword overlap with profile evidence gate"
                : "TF-IDF ordering with Java profile evidence gate";
        return new MentorDiscoveryResponse(results, method, fallback,
                QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null,
                results.isEmpty() ? "No approved alumni met the configured relevance threshold." : null,
                List.of());
    }

    private MentorRecommendation recommendation(String goal, MentorCandidate c, double score) {
        Set<String> terms = terms(goal);
        List<String> evidence = new ArrayList<>();
        c.skills().stream().filter(skill -> matches(terms, skill))
                .forEach(skill -> evidence.add("Skill: " + skill));
        if (matches(terms, c.jobTitle())) evidence.add("Current role: " + c.jobTitle());
        if (matches(terms, c.company())) evidence.add("Company: " + c.company());
        if (matches(terms, c.interests())) evidence.add("Career interest: " + c.interests());
        if (matches(terms, c.bio())) evidence.add("Profile bio mentions a related term");
        String firstName = c.name() == null || c.name().isBlank() ? "there" : c.name().trim().split("\\s+")[0];
        String topic = evidence.isEmpty() ? "Ask about their current role and the path into this field." : "Ask how their experience with " + evidence.get(0).replaceFirst("^[^:]+: ", "") + " shaped their career path.";
        String title = c.jobTitle() == null || c.jobTitle().isBlank() ? "your career" : c.jobTitle();
        String draft = "Hi " + firstName + ", I’m exploring " + goal.trim() + " and noticed your experience as " + title + ". Would you be open to sharing a little about your path and advice for someone getting started?";
        return new MentorRecommendation(c.id(), c.name(), c.jobTitle(), c.company(), c.skills(),
                Math.round(score * 1000.0) / 10.0, evidence,
                evidence.isEmpty() ? "No direct profile evidence matched the entered goal; review this profile before reaching out." : "Based only on public professional profile fields; availability and mentoring preference are unknown.",
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
        if (directOverlap == 0) return 0;
        Set<String> bioTerms = terms(Objects.toString(c.bio(), ""));
        long supportingOverlap = terms.stream().filter(bioTerms::contains).count();
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
