package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Conservative Phase 1 policy for stateless, reusable Knowledge Companion answers. */
@Component
public class KnowledgeAnswerCachePolicy {
    public static final int CACHE_SCHEMA_VERSION = 1;
    public static final String PROMPT_VERSION = "grounded-answer-v1";
    public static final String RETRIEVAL_VERSION = "knowledge-retrieval-v1";
    public static final String ANSWER_FORMAT_VERSION = "extractive-citations-v1";
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}+#.]+");
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?\\d[ .()-]?){9,15}(?!\\d)");
    private static final Pattern YEAR = Pattern.compile("\\b20\\d{2}\\b");
    private static final Set<String> PERSONAL_MARKERS = Set.of(
            "my", "mine", "myself", "personal", "profile", "application-status",
            "selection-probability", "chance-of-selection", "will-i-get", "am-i-likely",
            "based-on-my", "for-me", "about-me", "my-skills", "my-experience", "my-application");
    private static final Set<String> SENSITIVE_MARKERS = Set.of(
            "verification", "verify", "evidence", "proof", "uploaded", "upload", "private",
            "message", "conversation", "deadline", "opening", "openings", "currently", "latest",
            "right-now", "hiring-now", "application-deadline", "salary", "compensation", "visa");
    private static final Set<String> FILLER = Set.of("how", "what", "should", "could", "would", "can",
            "do", "does", "did", "i", "you", "we", "the", "a", "an", "for", "to", "of", "in",
            "on", "at", "with", "about", "please", "me", "some", "advice", "tips", "guide");
    private static final Set<String> PREP_WORDS = Set.of("prepare", "preparing", "preparation", "study", "practice", "prep");
    private static final Set<String> INTERVIEW_WORDS = Set.of("interview", "interviews", "interviewing");
    private static final Set<String> ROLE_WORDS = Set.of("intern", "internship", "swe", "sde", "engineer", "engineering");
    private static final Pattern COUNTRY = Pattern.compile("(?i)\\b(india|united states|usa|uk|canada|australia|singapore|germany)\\b");
    private static final Pattern SELF_CONTEXT = Pattern.compile(
            "(?i)\\b(i am|i'm|i have|i've|i work|i studied|i graduated|i got|i received|my current|my background|my experience|my skills)\\b");

    private final int stableDays;
    private final int preparationDays;
    private final int companyDays;
    private final int cycleDays;

    public KnowledgeAnswerCachePolicy(
            @Value("${app.knowledge.cache.ttl.stable-days:30}") int stableDays,
            @Value("${app.knowledge.cache.ttl.preparation-days:14}") int preparationDays,
            @Value("${app.knowledge.cache.ttl.company-days:7}") int companyDays,
            @Value("${app.knowledge.cache.ttl.cycle-days:3}") int cycleDays) {
        this.stableDays = boundedDays(stableDays, 30);
        this.preparationDays = boundedDays(preparationDays, 14);
        this.companyDays = boundedDays(companyDays, 7);
        this.cycleDays = boundedDays(cycleDays, 3);
    }

    public Decision decide(String question) {
        if (question == null || question.isBlank() || question.length() > 500
                || EMAIL.matcher(question).find() || PHONE.matcher(question).find()) return Decision.bypass("unsafe_input");
        String normalized = normalize(question);
        Set<String> tokens = tokens(normalized);
        if (tokens.stream().anyMatch(PERSONAL_MARKERS::contains)
                || tokens.stream().anyMatch(SENSITIVE_MARKERS::contains)
                || SELF_CONTEXT.matcher(question).find()
                || normalized.matches(".*\\b(application status|selection probability|chance of selection|my chances|current openings|hiring now|application deadline)\\b.*")
                || normalized.contains("my resume") || normalized.contains("about me")
                || normalized.contains("for me") || normalized.contains("my profile")) return Decision.bypass("personal_or_time_sensitive");

        String intent = intent(tokens, normalized);
        if (intent == null) return Decision.bypass("unknown_intent");
        boolean companySpecific = normalized.matches(".*\\b(at|for)\\s+[\\p{L}][\\p{L}0-9&.+ -]{1,50}.*")
                || normalized.contains("internship") || normalized.contains("intern");
        boolean cycleSpecific = YEAR.matcher(normalized).find() || normalized.matches(".*\\b(20\\d{2}|fall|spring|summer|winter)\\s*(cycle|recruiting|hiring|internship)?.*");
        return new Decision(true, intent, canonicalTopic(tokens, normalized, intent), country(normalized),
                years(normalized), cycleSpecific ? cycleDays : companySpecific ? companyDays
                : intent.equals("INTERVIEW_PREPARATION") || intent.equals("INTERNSHIP_GUIDANCE")
                ? preparationDays : stableDays, companySpecific, cycleSpecific, null, normalized);
    }

    public String key(Decision decision, List<KnowledgeRetrievalResponse.Source> sources,
                      String providerId, String modelId) {
        if (decision == null || !decision.eligible() || sources == null || sources.isEmpty())
            throw new IllegalArgumentException("Cache key requires an eligible question and retrieved sources");
        List<String> sourceContext = new java.util.ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            sourceContext.add(String.join("|", Integer.toString(index),
                    Long.toString(source.contributionId()), Integer.toString(source.version()),
                    normalize(source.category()), normalize(source.companyName()), normalize(source.jobRole())));
        }
        String material = String.join("\n", "cache=" + CACHE_SCHEMA_VERSION,
                "prompt=" + PROMPT_VERSION,
                "retrieval=" + RETRIEVAL_VERSION,
                "answer_format=" + ANSWER_FORMAT_VERSION,
                "intent=" + decision.intent(),
                // The exact normalized question is hashed but never persisted as question text.
                "question=" + decision.normalizedQuestion(),
                "country=" + decision.country(),
                "cycle=" + decision.cycle(),
                "provider=" + normalize(providerId),
                "model=" + normalize(modelId),
                "sources=" + String.join(";", sourceContext));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String normalizeQuestion(String question) {
        return normalize(question);
    }

    public String canonicalForm(Decision decision, List<KnowledgeRetrievalResponse.Source> sources) {
        if (decision == null || !decision.eligible()) return "";
        String topic = canonicalTopicForSources(decision.canonicalTopic(), sources);
        String value = topic.isBlank() ? decision.intent() : decision.intent() + ":" + topic;
        return value.substring(0, Math.min(500, value.length()));
    }

    private static String intent(Set<String> tokens, String normalized) {
        if (tokens.contains("resume") || tokens.contains("cv")) {
            return tokens.stream().anyMatch(Set.of("write", "writing", "format", "general", "improve", "advice", "tips")::contains)
                    ? "GENERAL_RESUME_GUIDANCE" : null;
        }
        boolean prep = tokens.stream().anyMatch(PREP_WORDS::contains)
                || normalized.contains("learning path") || normalized.contains("roadmap");
        if ((tokens.contains("internship") || tokens.contains("intern")) && prep)
            return "INTERNSHIP_PREPARATION";
        boolean interview = tokens.stream().anyMatch(INTERVIEW_WORDS::contains);
        if (interview && prep) return "INTERVIEW_PREPARATION";
        if ((tokens.contains("dsa") || tokens.contains("algorithm") || tokens.contains("algorithms")
                || tokens.contains("data-structures") || tokens.contains("data") && tokens.contains("structures")) && prep)
            return "TECHNICAL_PREPARATION";
        if (tokens.contains("roadmap") || normalized.contains("learning path") || normalized.contains("career plan"))
            return "CAREER_ROADMAP";
        if ((tokens.contains("internship") || tokens.contains("intern"))
                && tokens.stream().anyMatch(Set.of("apply", "application", "find", "get", "prepare", "preparation", "advice", "guide", "roadmap")::contains))
            return "INTERNSHIP_GUIDANCE";
        if (tokens.contains("mentorship") && tokens.stream().anyMatch(Set.of("find", "build", "start", "advice", "guide")::contains))
            return "MENTORSHIP_GUIDANCE";
        return null;
    }

    private static String canonicalTopic(Set<String> tokens, String normalized, String intent) {
        var matcher = TOKEN.matcher(normalized);
        List<String> retained = new java.util.ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (FILLER.contains(token) || PERSONAL_MARKERS.contains(token)) continue;
            if (PREP_WORDS.contains(token) || INTERVIEW_WORDS.contains(token)
                    || Set.of("roadmap", "learning", "path").contains(token)) continue;
            if (intent.equals("INTERNSHIP_GUIDANCE") && Set.of("intern", "internship", "apply", "application", "find", "get").contains(token)) continue;
            retained.add(switch (token) {
                case "internship" -> "intern";
                case "interviews", "interviewing" -> "interview";
                case "preparing", "preparation", "study", "practice" -> "prep";
                case "algorithms" -> "algorithm";
                default -> token;
            });
        }
        return String.join(" ", retained);
    }

    private static String canonicalTopicForSources(String topic, List<KnowledgeRetrievalResponse.Source> sources) {
        Set<String> contextWords = new java.util.HashSet<>();
        for (var source : sources) {
            contextWords.addAll(tokens(normalize(source.companyName())));
            Set<String> roleWords = tokens(normalize(source.jobRole()));
            contextWords.addAll(roleWords);
            String role = normalize(source.jobRole());
            if (role.contains("engineer") || role.contains("engineering") || role.contains("software developer"))
                contextWords.addAll(Set.of("swe", "sde", "software", "engineering", "engineer", "developer"));
            if (role.contains("intern")) contextWords.addAll(Set.of("intern", "internship"));
        }
        var matcher = TOKEN.matcher(topic);
        List<String> retained = new java.util.ArrayList<>();
        while (matcher.find()) if (!contextWords.contains(matcher.group().toLowerCase(Locale.ROOT)))
            retained.add(matcher.group().toLowerCase(Locale.ROOT));
        return String.join(" ", retained);
    }

    private static String normalize(String input) {
        if (input == null) return "";
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^\\p{L}\\p{N}+#.]+", " ");
        return normalized.strip().replaceAll("\\s+", " ");
    }

    private static Set<String> tokens(String normalized) {
        var matcher = TOKEN.matcher(normalized);
        Set<String> result = new TreeSet<>();
        while (matcher.find()) result.add(matcher.group().replace('.', '-'));
        return result;
    }

    private static String country(String normalized) {
        var matcher = COUNTRY.matcher(normalized);
        return matcher.find() ? normalize(matcher.group()).replace(' ', '-') : "";
    }

    private static String years(String normalized) {
        var matcher = YEAR.matcher(normalized);
        return matcher.find() ? matcher.group() : "";
    }

    private static int boundedDays(int value, int fallback) {
        return value < 1 || value > 30 ? fallback : value;
    }

    public record Decision(boolean eligible, String intent, String canonicalTopic, String country,
                           String cycle, int ttlDays, boolean companySpecific,
                           boolean cycleSpecific, String reason, String normalizedQuestion) {
        static Decision bypass(String reason) { return new Decision(false, "", "", "", "", 0, false, false, reason, ""); }
        public Duration ttl() { return Duration.ofDays(ttlDays); }
        public String canonicalForm() {
            if (!eligible) return "";
            String value = canonicalTopic.isBlank() ? intent : intent + ":" + canonicalTopic;
            return value.substring(0, Math.min(500, value.length()));
        }
    }
}
