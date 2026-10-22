package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.dto.QuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerAssistantServiceTest {
    private final MentorDiscoveryService mentorDiscovery = mock(MentorDiscoveryService.class);
    private final CareerAssistantService assistant = new CareerAssistantService(
            new QuestionIntentClassifier(), mentorDiscovery,
            new GeneralQuestionAnswerService());

    @Test
    void generalQuestionReturnsUnavailableWithoutFallingThroughToRanking() {
        var response = assistant.respond("Who is the Prime Minister of India?");

        assertEquals(QuestionIntent.GENERAL_KNOWLEDGE, response.intent());
        assertTrue(response.recommendations().isEmpty());
        assertTrue(response.message().contains("not configured yet"));
        assertTrue(response.message().contains("No alumni were searched"));
        assertTrue(response.sources().isEmpty());
        verifyNoInteractions(mentorDiscovery);
    }

    @Test
    void arithmeticAndGreetingAlsoAvoidMentorRanking() {
        var arithmetic = assistant.respond("What is 2 + 2?");
        var greeting = assistant.respond("Hi");

        assertEquals(QuestionIntent.GENERAL_KNOWLEDGE, arithmetic.intent());
        assertEquals(QuestionIntent.GREETING_OR_SMALLTALK, greeting.intent());
        assertNotNull(greeting.answer());
        verifyNoInteractions(mentorDiscovery);
    }

    @Test
    void unclearQuestionReturnsClarificationWithoutMentorRanking() {
        var response = assistant.respond("What should I do?");

        assertEquals(QuestionIntent.UNSUPPORTED_OR_UNCLEAR, response.intent());
        assertTrue(response.needsClarification());
        assertNotNull(response.clarificationQuestion());
        assertTrue(response.recommendations().isEmpty());
        verifyNoInteractions(mentorDiscovery);
    }

    @Test
    void careerQuestionUsesMentorDiscoveryAndPreservesLegacyFields() {
        var ranking = new MentorDiscoveryResponse(List.of(), "Java keyword overlap", true);
        when(mentorDiscovery.discover("How should I prepare for software engineering internships?"))
                .thenReturn(ranking);

        var response = assistant.respond("How should I prepare for software engineering internships?");

        assertEquals(QuestionIntent.CAREER_MENTOR_SEARCH, response.intent());
        assertEquals("Java keyword overlap", response.rankingMethod());
        assertTrue(response.fallbackUsed());
        verify(mentorDiscovery).discover("How should I prepare for software engineering internships?");
    }

    @Test
    void explicitAlumniQuestionIsOnlyUsedForDiscoveryAndIsNotSent() {
        var ranking = new MentorDiscoveryResponse(List.of(), "Java keyword overlap", true);
        when(mentorDiscovery.discover("I want to message a mentor about my career options"))
                .thenReturn(ranking);

        var response = assistant.respond("I want to message a mentor about my career options");

        assertEquals(QuestionIntent.ALUMNI_QUESTION, response.intent());
        assertNull(response.answer());
        assertTrue(response.message().contains("not sent"));
        verify(mentorDiscovery).discover("I want to message a mentor about my career options");
    }
}
