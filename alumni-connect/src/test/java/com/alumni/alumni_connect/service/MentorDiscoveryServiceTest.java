package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.repository.UserSkillRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MentorDiscoveryServiceTest {
    @Mock UserRepository users;
    @Mock UserSkillRepository userSkills;
    @Mock CurrentUserService currentUser;
    @Mock RestTemplate restTemplate;
    @InjectMocks MentorDiscoveryService service;

    @Test
    void fallbackRanksApprovedProfilesAndUsesOnlyMatchingEvidence() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User alum = mock(User.class);
        when(alum.getId()).thenReturn(21L);
        when(alum.getName()).thenReturn("Sam Mentor");
        when(alum.getSkills()).thenReturn("Python, data engineering");
        when(alum.getJobRole()).thenReturn("Data Engineer");
        when(alum.getCompany()).thenReturn("Example Co");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(alum));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());

        var response = service.discover("How do I start in data engineering?");

        assertTrue(response.fallbackUsed());
        assertEquals(1, response.recommendations().size());
        assertTrue(response.recommendations().getFirst().evidence().stream()
                .anyMatch(evidence -> evidence.contains("Data Engineer") || evidence.contains("data engineering")));
        assertTrue(response.recommendations().getFirst().messageDraft().contains("Sam"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void emptyApprovedDirectoryProducesNoRecommendations() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of());

        var response = service.discover("product design");

        assertTrue(response.recommendations().isEmpty());
        assertFalse(response.fallbackUsed());
        verifyNoInteractions(userSkills, restTemplate);
    }

    @Test
    void malformedAiRankingFallsBackToCompleteLocalRanking() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam Mentor", "data engineering", "Data Engineer");
        User unrelated = alumni(22L, "Alex Mentor", "gardening", "Gardener");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant, unrelated));
        when(userSkills.findByUser_IdIn(List.of(21L, 22L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(22L),
                "scores", Map.of("22", 0.9))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering career");

        assertTrue(response.fallbackUsed());
        assertEquals("Transparent profile keyword overlap (fallback)", response.rankingMethod());
        assertEquals(List.of(21L, 22L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertFalse(response.recommendations().getFirst().evidence().isEmpty());
    }

    @Test
    void validAiResponseUsesScoresAndSendsOnlyRankingFields() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam PrivateName", "data engineering", "Data Engineer");
        User unrelated = alumni(22L, "Alex OtherName", "gardening", "Gardener");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant, unrelated));
        when(userSkills.findByUser_IdIn(List.of(21L, 22L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(22L, 21L),
                "scores", Map.of("21", 0.85, "22", 0.1))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering career");

        assertFalse(response.fallbackUsed());
        assertEquals(List.of(22L, 21L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertEquals(10.0, response.recommendations().getFirst().relevanceEstimate());
        assertEquals(85.0, response.recommendations().get(1).relevanceEstimate());
        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), request.capture(),
                any(ParameterizedTypeReference.class), any(Object[].class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getValue().getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidates");
        assertFalse(candidates.getFirst().containsKey("name"));
        assertFalse(candidates.getFirst().containsKey("email"));
    }

    @Test
    void invalidAiScoreFallsBackToTheLocalEstimate() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam Mentor", "data engineering", "Data Engineer");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(21L),
                "scores", Map.of("21", Double.NaN))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering");

        assertTrue(response.fallbackUsed());
        assertTrue(response.recommendations().getFirst().relevanceEstimate() > 0);
    }

    private User alumni(long id, String name, String skills, String jobRole) {
        User alum = mock(User.class);
        when(alum.getId()).thenReturn(id);
        when(alum.getName()).thenReturn(name);
        when(alum.getSkills()).thenReturn(skills);
        when(alum.getJobRole()).thenReturn(jobRole);
        when(alum.getCompany()).thenReturn("Example Co");
        return alum;
    }
}
