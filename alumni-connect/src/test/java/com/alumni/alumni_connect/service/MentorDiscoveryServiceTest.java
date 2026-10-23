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
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
        assertEquals("No approved alumni available", response.rankingMethod());
        assertTrue(response.message().contains("no approved alumni"));
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
        assertEquals("Java keyword overlap with profile evidence gate", response.rankingMethod());
        assertEquals(List.of(21L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertFalse(response.recommendations().getFirst().evidence().isEmpty());
    }

    @Test
    void validAiResponseOrdersOnlyRelevantCandidatesAndSendsNoNamesOrEmails() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam PrivateName", "data engineering analytics", "Data Engineer");
        User otherRelevant = alumni(22L, "Alex OtherName", "data analytics", "Data Scientist");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant, otherRelevant));
        when(userSkills.findByUser_IdIn(List.of(21L, 22L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        ReflectionTestUtils.setField(service, "rankingStrategy", "semantic");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(22, 21),
                "scores", Map.of("21", 0.85, "22", 0.1))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering analytics career");

        assertFalse(response.fallbackUsed());
        assertEquals("Sentence embedding ordering with Java profile evidence gate", response.rankingMethod());
        assertEquals(List.of(22L, 21L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertEquals(22L, response.recommendations().getFirst().id());
        assertEquals(50.0, response.recommendations().getFirst().relevanceEstimate());
        assertEquals(100.0, response.recommendations().get(1).relevanceEstimate());
        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), request.capture(),
                any(ParameterizedTypeReference.class), any(Object[].class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getValue().getBody();
        assertEquals("semantic", payload.get("strategy"));
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

    @Test
    void pythonVectorizationFailureUsesLocalRankingFallback() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam Mentor", "data engineering", "Data Engineer");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenThrow(new org.springframework.web.client.HttpServerErrorException(
                        HttpStatus.SERVICE_UNAVAILABLE));

        var response = service.discover("data engineering");

        assertTrue(response.fallbackUsed());
        assertEquals("Java keyword overlap with profile evidence gate", response.rankingMethod());
        assertEquals(1, response.recommendations().size());
    }

    @Test
    void rankingPayloadCapsSkillsWithoutFallingBack() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        String skills = String.join(",", IntStream.range(0, 60)
                .mapToObj(index -> "s" + index).toList());
        User relevant = alumni(21L, "Sam Mentor", skills, "Data Engineer");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(21L),
                "scores", Map.of("21", 0.75))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering");

        assertFalse(response.fallbackUsed());
        assertEquals(60, response.recommendations().getFirst().skills().size());
        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), request.capture(),
                any(ParameterizedTypeReference.class), any(Object[].class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getValue().getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) payload.get("candidates");
        assertEquals(50, ((List<?>) candidates.getFirst().get("skills")).size());
    }

    @Test
    void unrelatedApprovedProfilesAreFilteredBeforeCallingPython() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User unrelated = alumni(21L, "Sam Mentor", "gardening", "Gardener");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(unrelated));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);

        var response = service.discover("How should I prepare for software engineering internships?");

        assertTrue(response.recommendations().isEmpty());
        assertTrue(response.message().contains("none had enough matching"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void configuredMinimumRelevanceCanRequireMoreThanOneMatchedTerm() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User weak = alumni(21L, "Sam Mentor", "data analytics", "Data Analyst");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(weak));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "minimumRelevance", 0.75);

        var response = service.discover("data engineering");

        assertTrue(response.recommendations().isEmpty());
        assertTrue(response.message().contains("none had enough matching"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void quantumComputingDoesNotMatchCloudProfileInAnyPythonMode() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User cloudAlum = alumni(105L, "Synthetic Cloud Alum", "AWS, Docker, Kubernetes, Terraform",
                "DevOps Engineer");
        when(cloudAlum.getInterests()).thenReturn("cloud computing and DevOps");
        when(cloudAlum.getBio()).thenReturn("Automates cloud infrastructure and deployment pipelines.");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(cloudAlum));
        when(userSkills.findByUser_IdIn(List.of(105L))).thenReturn(List.of());

        for (boolean enabled : List.of(false, true)) {
            ReflectionTestUtils.setField(service, "aiEnabled", enabled);
            var response = service.discover("How can I get a job in quantum computing?");
            assertTrue(response.recommendations().isEmpty());
            assertTrue(response.message().contains("none had enough matching"));
        }
        verifyNoInteractions(restTemplate);
    }

    @Test
    void localFallbackRanksInterviewParaphrasesAgainstProfileEvidence() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User interviewAlum = alumni(102L, "Synthetic Interview Alum",
                "DSA, Algorithms, Coding Interviews, Python, Online Assessments", "Software Engineer");
        when(interviewAlum.getBio()).thenReturn(
                "Works on coding rounds, problem solving, and software engineering internship interviews.");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(interviewAlum));
        when(userSkills.findByUser_IdIn(List.of(102L))).thenReturn(List.of());

        for (String question : List.of(
                "I need help with DSA interview preparation.",
                "How should I prepare for coding rounds?",
                "I struggle with problem-solving questions in technical interviews.",
                "Can someone guide me for software engineering OAs?",
                "How do I prep for a softwear enginer interveiw?")) {
            var response = service.discover(question);
            assertEquals(List.of(102L), response.recommendations().stream()
                    .map(recommendation -> recommendation.id()).toList(), question);
            assertTrue(response.fallbackUsed(), question);
        }
        verifyNoInteractions(restTemplate);
    }

    @Test
    void knownAiAndMachineLearningTermsCanMatchWithoutAnEmbeddingModel() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam Mentor", "predictive models, data pipelines",
                "Machine learning engineer");
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500)))
                .thenReturn(List.of(relevant));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());

        var response = service.discover("I want to work in AI. What should I learn?");

        assertEquals(List.of(21L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertTrue(response.recommendations().getFirst().relevanceEstimate() > 0);
    }

    @Test
    void nonIntegralRankingIdIsRejectedAndFallsBackSafely() {
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
                "rankedIds", List.of(21.5),
                "scores", Map.of("21", 0.75))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering");

        assertTrue(response.fallbackUsed());
        assertEquals(List.of(21L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
    }

    @Test
    void duplicateRankingIdsAreRejectedAndFallsBackSafely() {
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
                "rankedIds", List.of(21, 21),
                "scores", Map.of("21", 0.75))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering");

        assertTrue(response.fallbackUsed());
        assertEquals(List.of(21L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
    }

    @Test
    void infiniteRankingScoreIsRejectedAndFallsBackSafely() {
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
                "rankedIds", List.of(21),
                "scores", Map.of("21", Double.POSITIVE_INFINITY))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering");

        assertTrue(response.fallbackUsed());
        assertEquals(1, response.recommendations().size());
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
