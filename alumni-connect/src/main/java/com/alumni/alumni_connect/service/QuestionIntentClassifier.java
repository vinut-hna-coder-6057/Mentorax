package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.QuestionIntent;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class QuestionIntentClassifier {
    private static final Pattern GREETING = Pattern.compile(
            "^(hi|hello|hey|good morning|good afternoon|good evening|how are you)[!.?\\s]*$");
    private static final Pattern ALUMNI_CONTACT = Pattern.compile(
            "\\b(ask|message|send|contact|reach out to|write to)\\b.{0,60}\\b(alumni|alumnus|alumna|mentor|graduate)\\b"
                    + "|\\b(alumni|alumnus|alumna|mentor|graduate)\\b.{0,60}\\b(message|ask|send)\\b");
    private static final Pattern CAREER_CONTEXT = Pattern.compile(
            "\\b(careers?|career change|career switch|internships?|intern|jobs?|job search|interviews?|referrals?|dsa|resume|cv|portfolio|placements?|placement preparation|promotion|salary|leadership|professional development|software engineering|software developer|machine learning|data engineer|data engineering|data science|data analyst|product management|product manager|designer|developer|engineer|roles?|industry|work in|join|hiring)\\b");
    private static final Pattern TECHNICAL_CAREER_TOPIC = Pattern.compile(
            "\\b(java|python|javascript|typescript|react|node(?:\\.js)?|backend|back end|full stack|fullstack|cloud|devops|system design|cybersecurity|cyber security|artificial intelligence|machine learning|\\bai\\b|\\bml\\b|dsa|data structures|algorithms?|coding rounds?|problem solving|online assessments?|\\boas?\\b)\\b");
    private static final Pattern LEARNING_ACTION = Pattern.compile(
            "\\b(prepare|preparing|preparation|prep|improve|improving|learn|become|becoming|switch|switching|transition|transitioning|move into|break into|enter|grow|advance|apply|applying|write|review|feedback|practice|study|choose|negotiate|ask|request|need|get|find|build|guide|help|struggle|combine|start|starting|looking)\\b");
    private static final Pattern MENTORSHIP_CONTEXT = Pattern.compile(
            "\\b(mentor|mentorship|guidance|career advice|professional advice|career path)\\b");
    private static final Pattern GENERAL_QUESTION = Pattern.compile(
            "^(who|what|when|where|why|which|how many|how much|how does|what is|what are)\\b");
    private static final Pattern ARITHMETIC = Pattern.compile(
            "^(what is\\s+)?[-+]?\\d+(?:\\s*[+*/-]\\s*[-+]?\\d+)+(?:\\s*[?=])?$");
    private static final Pattern VAGUE_QUESTION = Pattern.compile(
            "^(what should i do|what do you think|can you help me|help me|i need advice)[!.?\\s]*$");
    private static final Set<String> UNSUPPORTED_PHRASES = Set.of(
            "tell me a joke", "write me a poem", "generate an image", "hack an account");

    public Classification classify(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return unclear(true);
        }

        if (GREETING.matcher(normalized).matches()) {
            return new Classification(QuestionIntent.GREETING_OR_SMALLTALK, false, null);
        }

        if (ALUMNI_CONTACT.matcher(normalized).find()) {
            return new Classification(QuestionIntent.ALUMNI_QUESTION, false, null);
        }

        boolean careerContext = CAREER_CONTEXT.matcher(normalized).find();
        boolean action = LEARNING_ACTION.matcher(normalized).find();
        boolean mentorshipContext = MENTORSHIP_CONTEXT.matcher(normalized).find();
        boolean technicalTopic = TECHNICAL_CAREER_TOPIC.matcher(normalized).find();
        if ((careerContext && action) || (careerContext && mentorshipContext)
                || (technicalTopic && action) || (technicalTopic && mentorshipContext)
                || (action && mentorshipContext)) {
            return new Classification(QuestionIntent.CAREER_MENTOR_SEARCH, false, null);
        }

        if (VAGUE_QUESTION.matcher(normalized).matches()) {
            return unclear(true);
        }

        if (ARITHMETIC.matcher(normalized).matches()
                || GENERAL_QUESTION.matcher(normalized).find()) {
            return new Classification(QuestionIntent.GENERAL_KNOWLEDGE, false, null);
        }

        if (UNSUPPORTED_PHRASES.stream().anyMatch(normalized::contains)) {
            return unclear(false);
        }

        return unclear(true);
    }

    private Classification unclear(boolean needsClarification) {
        return new Classification(
                QuestionIntent.UNSUPPORTED_OR_UNCLEAR,
                needsClarification,
                needsClarification
                        ? "Is this a career question for finding alumni, or a general factual question?"
                        : null);
    }

    private String normalize(String input) {
        String value = Normalizer.normalize(input == null ? "" : input,
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        value = value.replaceAll("\\b(intership|internshp|internsip)\\b", "internship")
                .replaceAll("\\b(softwear|softare)\\b", "software")
                .replaceAll("\\b(interveiw|intreview)\\b", "interview")
                .replaceAll("\\b(enginer|engineerng)\\b", "engineer")
                .replaceAll("\\b(machin learning|machine learnin|machine learnng|machne learning)\\b", "machine learning")
                .replaceAll("[^\\p{L}\\p{N}+*/?=.-]+", " ")
                .replaceAll("\\s+", " ").trim();
        return value;
    }

    public record Classification(QuestionIntent intent, boolean needsClarification,
                                 String clarificationQuestion) {}
}
