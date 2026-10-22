package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.QuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionIntentClassifierTest {
    private final QuestionIntentClassifier classifier = new QuestionIntentClassifier();

    @Test
    void commonCareerQuestionsRouteToMentorDiscovery() {
        assertIntent("How should I prepare for software engineering internships?",
                QuestionIntent.CAREER_MENTOR_SEARCH);
        assertIntent("How can I improve my DSA and interview skills?",
                QuestionIntent.CAREER_MENTOR_SEARCH);
        assertIntent("What should I learn to become a machine learning engineer?",
                QuestionIntent.CAREER_MENTOR_SEARCH);
        assertIntent("I want to work in AI. What should I learn?",
                QuestionIntent.CAREER_MENTOR_SEARCH);
    }

    @Test
    void fixedEvaluationCareerParaphrasesRouteToMentorDiscovery() {
        for (String question : List.of(
                "How can I learn Java and backend development?",
                "How do I learn React for full-stack development?",
                "How can I learn cloud computing and DevOps?",
                "How can I improve my system design knowledge?",
                "How can I get started in cybersecurity?",
                "How can I build AI and machine-learning projects?",
                "I need help with DSA interview preparation.",
                "How should I prepare for coding rounds?",
                "I struggle with problem-solving questions in technical interviews.",
                "Can someone guide me for software engineering OAs?",
                "How do I prep for a softwear enginer interveiw?",
                "How can I combine Java and React for full-stack work?",
                "How do I learn Java?")) {
            assertIntent(question, QuestionIntent.CAREER_MENTOR_SEARCH);
        }
    }

    @Test
    void commonTyposAreNormalizedBeforeClassification() {
        assertIntent("How do I prepare for a softwear intership?",
                QuestionIntent.CAREER_MENTOR_SEARCH);
        assertIntent("How do I prepare for a software interveiw?",
                QuestionIntent.CAREER_MENTOR_SEARCH);
    }

    @Test
    void generalQuestionsDoNotBecomeMentorSearches() {
        assertIntent("Who is the Prime Minister of India?", QuestionIntent.GENERAL_KNOWLEDGE);
        assertIntent("What is 2 + 2?", QuestionIntent.GENERAL_KNOWLEDGE);
        assertIntent("What is the weather today?", QuestionIntent.GENERAL_KNOWLEDGE);
        assertIntent("Who won yesterday's cricket match?", QuestionIntent.GENERAL_KNOWLEDGE);
        assertIntent("What is machine learning?", QuestionIntent.GENERAL_KNOWLEDGE);
    }

    @Test
    void greetingAndExplicitAlumniMessageHaveSeparateIntents() {
        assertIntent("Hi", QuestionIntent.GREETING_OR_SMALLTALK);
        assertIntent("I want to message a mentor about my career options",
                QuestionIntent.ALUMNI_QUESTION);
    }

    @Test
    void ambiguousAndUnsupportedRequestsAreNotSilentlyRanked() {
        var ambiguous = classifier.classify("What should I do?");
        assertEquals(QuestionIntent.UNSUPPORTED_OR_UNCLEAR, ambiguous.intent());
        assertTrue(ambiguous.needsClarification());
        assertTrue(ambiguous.clarificationQuestion().contains("career question"));
        assertEquals(QuestionIntent.UNSUPPORTED_OR_UNCLEAR,
                classifier.classify("Tell me a joke").intent());
        assertTrue(classifier.classify("   ").needsClarification());
    }

    private void assertIntent(String question, QuestionIntent expected) {
        assertEquals(expected, classifier.classify(question).intent(), question);
    }
}
