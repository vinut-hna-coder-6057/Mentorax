package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalServiceTest {
    @Test
    void synonymAndStructuredSignalsFindEquivalentEligibleContribution() {
        KnowledgeContributionService contributions = mock(KnowledgeContributionService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUser()).thenReturn(new User("Student", "student@example.test",
                "hash", "STUDENT", "APPROVED"));
        KnowledgeContributionResponse relevant = contribution(81, "INTERVIEW_EXPERIENCE",
                "Technical assessment at Acme", "The online programming round included timed coding tasks.",
                "Acme", "Software Engineer Intern");
        KnowledgeContributionResponse unrelated = contribution(82, "PROJECT_EXPERIENCE",
                "Garden design", "Planned a community garden.", "GardenCo", "Landscape Designer");
        when(contributions.listAiEligible(0, 50)).thenReturn(List.of(relevant, unrelated));
        when(contributions.recheckAiEligible(anyList())).thenAnswer(invocation -> List.of(relevant));

        var response = new KnowledgeRetrievalService(contributions, currentUser)
                .retrieve("SWE internship OA at Acme", null, 0, 5);

        assertEquals(1, response.sources().size());
        assertEquals(81, response.sources().getFirst().contributionId());
        assertTrue(response.sources().getFirst().matchedFields().contains("exact company"));
        verify(contributions).recheckAiEligible(argThat(refs -> refs.size() == 1
                && refs.getFirst().contributionId() == 81 && refs.getFirst().version() == 1));
    }

    @Test
    void irrelevantKeywordLikeCandidateDoesNotForceAnAnswerSource() {
        KnowledgeContributionService contributions = mock(KnowledgeContributionService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUser()).thenReturn(new User("Student", "student@example.test",
                "hash", "STUDENT", "APPROVED"));
        when(contributions.listAiEligible(0, 50)).thenReturn(List.of(contribution(83, "GENERAL",
                "Company hiring folklore", "No technical or interview experience is described.",
                "UnknownCo", "Unknown Role")));

        var response = new KnowledgeRetrievalService(contributions, currentUser)
                .retrieve("What are the exact hiring rules at Acme in 2026?", null, 0, 5);

        assertTrue(response.sources().isEmpty());
        verify(contributions, never()).recheckAiEligible(anyList());
    }

    @Test
    void selectedCandidateIsDiscardedWhenEligibilityRecheckNoLongerReturnsItsVersion() {
        KnowledgeContributionService contributions = mock(KnowledgeContributionService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUser()).thenReturn(new User("Student", "student@example.test",
                "hash", "STUDENT", "APPROVED"));
        KnowledgeContributionResponse eligibleAtSearch = contribution(84, "INTERVIEW_EXPERIENCE",
                "Acme engineering interview", "The interview used a coding round.", "Acme", "Software Engineer");
        when(contributions.listAiEligible(0, 50)).thenReturn(List.of(eligibleAtSearch));
        when(contributions.recheckAiEligible(anyList())).thenReturn(List.of());

        var response = new KnowledgeRetrievalService(contributions, currentUser)
                .retrieve("Acme software engineer coding round", null, 0, 5);

        assertTrue(response.sources().isEmpty());
        verify(contributions).recheckAiEligible(argThat(refs -> refs.size() == 1
                && refs.getFirst().contributionId() == 84));
    }

    private static KnowledgeContributionResponse contribution(long id, String category, String title,
            String narrative, String company, String role) {
        LocalDateTime now = LocalDateTime.now();
        return new KnowledgeContributionResponse(id, 1, 1, category, title, narrative, company, role,
                null, "PUBLISHED", true, true, now, now, now, List.of());
    }
}
