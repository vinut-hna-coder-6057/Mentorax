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
import org.springframework.data.domain.PageRequest;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MentorDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(MentorDiscoveryService.class);
    private final UserRepository users;
    private final UserSkillRepository userSkills;
    private final CurrentUserService currentUser;
    private final RestTemplate restTemplate;
    @Value("${app.ai.enabled:false}") private boolean aiEnabled;
    @Value("${app.ai.service-url:http://127.0.0.1:8090}") private String aiServiceUrl;

    public MentorDiscoveryService(UserRepository users, UserSkillRepository userSkills,
                                  CurrentUserService currentUser, RestTemplate restTemplate) {
        this.users = users; this.userSkills = userSkills; this.currentUser = currentUser;
        this.restTemplate = restTemplate;
    }

    @Transactional(readOnly = true)
    public MentorDiscoveryResponse discover(String goal) {
        User student = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(student.getRole())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Student access required");
        }
        List<User> approved = users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500));
        if (approved.isEmpty()) return new MentorDiscoveryResponse(List.of(), "No eligible approved alumni found", false);
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

        List<Long> rankedIds = null;
        Map<Long, Double> aiScores = Map.of();
        boolean fallback = true;
        if (aiEnabled) {
            try {
                // Names are not needed for ranking; keep personally identifying data
                // inside Spring, which owns the candidate records and explanations.
                List<Map<String, Object>> rankingCandidates = candidates.stream().map(candidate -> {
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("id", candidate.id());
                    fields.put("jobTitle", candidate.jobTitle());
                    fields.put("company", candidate.company());
                    fields.put("bio", candidate.bio());
                    fields.put("skills", candidate.skills());
                    fields.put("interests", candidate.interests());
                    return fields;
                }).toList();
                Map<String, Object> payload = Map.of("goal", goal, "candidates", rankingCandidates);
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                        aiServiceUrl.replaceAll("/$", "") + "/rank", HttpMethod.POST,
                        new HttpEntity<>(payload, jsonHeaders()), new ParameterizedTypeReference<>() {});
                Object raw = response.getBody() == null ? null : response.getBody().get("rankedIds");
                if (raw instanceof List<?> ids) {
                    List<Long> validated = ids.stream().filter(Number.class::isInstance)
                            .map(Number.class::cast).map(Number::longValue)
                            .filter(id -> candidates.stream().anyMatch(c -> c.id().equals(id))).distinct().toList();
                    Set<Long> expectedIds = candidates.stream().map(MentorCandidate::id)
                            .collect(Collectors.toSet());
                    Map<Long, Double> validatedScores = parseScores(
                            response.getBody().get("scores"), expectedIds);
                    boolean completeRanking = ids.size() == expectedIds.size()
                            && validated.size() == expectedIds.size()
                            && new HashSet<>(validated).equals(expectedIds);
                    if (completeRanking && validatedScores != null) {
                        rankedIds = validated;
                        aiScores = Map.copyOf(validatedScores);
                        fallback = false;
                    }
                }
            } catch (RuntimeException failure) {
                log.info("Optional mentor ranking service unavailable ({}); using local ranking",
                        failure.getClass().getSimpleName());
            }
        }
        if (rankedIds == null) rankedIds = candidates.stream().sorted(Comparator
                .comparingDouble((MentorCandidate c) -> localScore(goal, c)).reversed()
                .thenComparing(MentorCandidate::id)).map(MentorCandidate::id).toList();

        Map<Long, MentorCandidate> byId = candidates.stream().collect(Collectors.toMap(MentorCandidate::id, c -> c));
        final boolean usedFallback = fallback;
        final Map<Long, Double> validatedAiScores = aiScores;
        List<MentorRecommendation> results = rankedIds.stream().map(byId::get).filter(Objects::nonNull)
                .limit(10).map(candidate -> recommendation(goal, candidate,
                        usedFallback ? localScore(goal, candidate)
                                : validatedAiScores.getOrDefault(candidate.id(), 0.0))).toList();
        String method = fallback ? "Transparent profile keyword overlap (fallback)" : "TF-IDF cosine similarity over goal and public profile";
        return new MentorDiscoveryResponse(results, method, fallback);
    }

    private MentorRecommendation recommendation(String goal, MentorCandidate c, double score) {
        Set<String> terms = terms(goal);
        List<String> evidence = new ArrayList<>();
        c.skills().stream().filter(s -> terms.contains(normalize(s))).forEach(s -> evidence.add("Skill: " + s));
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

    private double localScore(String goal, MentorCandidate c) {
        Set<String> terms = terms(goal);
        if (terms.isEmpty()) return 0;
        String evidence = String.join(" ", c.skills()) + " " + Objects.toString(c.jobTitle(), "") + " " +
                Objects.toString(c.company(), "") + " " + Objects.toString(c.bio(), "") + " " + Objects.toString(c.interests(), "");
        Set<String> matched = terms(evidence);
        long overlap = terms.stream().filter(matched::contains).count();
        return (double) overlap / terms.size();
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
    private Set<String> terms(String value) { return Arrays.stream(normalize(value).split("\\s+")).filter(s -> s.length() > 2).collect(Collectors.toSet()); }
    private String normalize(String value) { return Normalizer.normalize(Objects.toString(value, "").toLowerCase(Locale.ROOT), Normalizer.Form.NFKC).replaceAll("[^\\p{L}\\p{N}]+", " ").trim(); }
    private HttpHeaders jsonHeaders() { HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); return headers; }
}
