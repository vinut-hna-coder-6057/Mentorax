package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.QuestionIntent;
import org.junit.jupiter.api.Test;

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
