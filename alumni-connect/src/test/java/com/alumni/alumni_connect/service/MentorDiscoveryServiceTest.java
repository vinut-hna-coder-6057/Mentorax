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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
    void profileBioCanProvidePositiveEvidenceWhenStructuredFieldsAreIncomplete() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User bioOnlyMatch = alumni(31L, "Ari Mentor", "illustration", "Visual Designer");
        when(bioOnlyMatch.getBio()).thenReturn("Works on digital accessibility for public services.");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(bioOnlyMatch));
        when(userSkills.findByUser_IdIn(List.of(31L))).thenReturn(List.of());

        var response = service.discover("How can I learn about digital accessibility?");

        assertEquals(List.of(31L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertTrue(response.recommendations().getFirst().evidence().stream()
                .anyMatch(evidence -> evidence.contains("Profile bio")));
    }

    @Test
    void emptyApprovedDirectoryProducesNoRecommendations() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of());

        var response = service.discover("product design");

        assertTrue(response.recommendations().isEmpty());
        assertFalse(response.fallbackUsed());
        assertEquals("No approved alumni available", response.rankingMethod());
        assertTrue(response.message().contains("no approved alumni"));
        verifyNoInteractions(userSkills, restTemplate);
    }

    @Test
    void discoversRelevantApprovedAlumnusBeyondFirstFiveHundredWithoutLoadingAllRows() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        List<User> firstPage = IntStream.rangeClosed(1, 500)
                .mapToObj(id -> alumni(id, "Unrelated " + id, "gardening", "Gardener"))
                .toList();
        User laterPageMatch = alumni(501, "Later Page Mentor", "data engineering", "Data Engineer");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(firstPage);
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 500L))
                .thenReturn(List.of(laterPageMatch));
        when(userSkills.findByUser_IdIn(anyList())).thenReturn(List.of());

        var response = service.discover("How do I prepare for data engineering roles?");

        assertEquals(List.of(501L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        verify(users).findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 500L);
        verify(users, never()).findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 1000L);
    }

    @Test
    void sendsTwoDatabasePagesInOneBoundedPythonRequestAndPreservesResults() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        List<User> firstPage = IntStream.rangeClosed(1, 500)
                .mapToObj(id -> alumni(id, "Mentor " + id, "data engineering", "Data Engineer")).toList();
        User laterPageMatch = alumni(501, "Later Page Mentor", "data engineering", "Data Engineer");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(firstPage);
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 500L))
                .thenReturn(List.of(laterPageMatch));
        when(userSkills.findByUser_IdIn(anyList())).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        Map<String, Object> firstPageResult = rankingResult(IntStream.rangeClosed(1, 500).boxed().toList());
        Map<String, Object> secondPageResult = rankingResult(List.of(501));
        doReturn(ResponseEntity.ok(Map.of("results", List.of(
                Map.of("result", firstPageResult), Map.of("result", secondPageResult)))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("data engineering careers");

        assertEquals(10, response.recommendations().size());
        assertTrue(response.fallbackUsed(), "page-relative TF-IDF cannot be globally merged safely");
        org.mockito.ArgumentCaptor<String> url = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(1)).exchange(url.capture(), eq(HttpMethod.POST), request.capture(),
                any(ParameterizedTypeReference.class), any(Object[].class));
        assertTrue(url.getValue().endsWith("/rank/batch"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getValue().getBody();
        assertEquals(2, ((List<?>) payload.get("requests")).size());
    }

    private Map<String, Object> rankingResult(List<Integer> ids) {
        Map<String, Object> scores = new HashMap<>();
        ids.forEach(id -> scores.put(String.valueOf(id), 0.8));
        return Map.of("rankedIds", ids, "scores", scores);
    }

    @Test
    void malformedAiRankingFallsBackToCompleteLocalRanking() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam Mentor", "data engineering", "Data Engineer");
        User unrelated = alumni(22L, "Alex Mentor", "gardening", "Gardener");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(relevant, otherRelevant));
        when(userSkills.findByUser_IdIn(List.of(21L, 22L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        ReflectionTestUtils.setField(service, "rankingStrategy", "semantic");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(22, 21),
                "scores", Map.of("21", 0.1, "22", 0.85),
                "semanticScores", Map.of("21", 0.1, "22", 0.85))))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(unrelated));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);

        var response = service.discover("How should I prepare for software engineering internships?");

        assertTrue(response.recommendations().isEmpty());
        assertTrue(response.message().contains("none had enough matching"));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void semanticRetrievalReceivesApprovedProfilesBeforeEvidenceAbstention() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User unrelated = alumni(41L, "Sam Mentor", "gardening", "Gardener");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(unrelated));
        when(userSkills.findByUser_IdIn(List.of(41L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "rankingStrategy", "semantic");
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of("rankedIds", List.of(41L), "scores", Map.of("41", 0.2),
                        "semanticScores", Map.of("41", 0.2))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("How should I prepare for software engineering internships?");

        assertTrue(response.recommendations().isEmpty());
        org.mockito.ArgumentCaptor<HttpEntity> request = org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), request.capture(),
                any(ParameterizedTypeReference.class), any(Object[].class));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getValue().getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sentCandidates = (List<Map<String, Object>>) payload.get("candidates");
        assertEquals(List.of(41L), sentCandidates.stream().map(candidate -> candidate.get("id")).toList());
    }

    @Test
    void semanticParaphraseCanPassWithSubstantiveProfileEvidenceButLowSimilarityAndNoMatchAbstain() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User paraphraseMatch = alumni(51L, "Mina Mentor", "TensorFlow, predictive modeling",
                "Machine Learning Engineer");
        User unrelated = alumni(52L, "Noah Mentor", "gardening", "Horticulturist");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(paraphraseMatch, unrelated));
        when(userSkills.findByUser_IdIn(List.of(51L, 52L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "rankingStrategy", "hybrid");
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of(
                "rankedIds", List.of(51L, 52L),
                "scores", Map.of("51", 0.65, "52", 0.1),
                "semanticScores", Map.of("51", 0.84, "52", 0.16))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var response = service.discover("I want to make computers learn from examples");

        assertEquals(List.of(51L), response.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
        assertEquals("Hybrid rank-percentile ordering with Java profile evidence gate", response.rankingMethod());
        var recommendation = response.recommendations().getFirst();
        assertTrue(recommendation.evidence().stream().anyMatch(evidence ->
                evidence.contains("Machine Learning Engineer") || evidence.contains("TensorFlow")),
                recommendation.evidence().toString());
        assertTrue(recommendation.uncertainty().contains("not a probability"));
        assertEquals(0.0, recommendation.relevanceEstimate(),
                "the displayed lexical overlap estimate remains separate from semantic similarity");
    }

    @Test
    void semanticAdmissionUsesInclusiveConfiguredThresholdBoundary() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User mentor = alumni(61L, "Ria Mentor", "predictive modeling", "Machine Learning Engineer");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(mentor));
        when(userSkills.findByUser_IdIn(List.of(61L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "rankingStrategy", "semantic");
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of("rankedIds", List.of(61L), "scores", Map.of("61", 0.9),
                        "semanticScores", Map.of("61", 0.7199))))
                .doReturn(ResponseEntity.ok(Map.of("rankedIds", List.of(61L), "scores", Map.of("61", 0.9),
                        "semanticScores", Map.of("61", 0.72))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var belowThreshold = service.discover("How do I start a career in data science?");
        var atThreshold = service.discover("How do I start a career in data science?");

        assertTrue(belowThreshold.recommendations().isEmpty());
        assertEquals(List.of(61L), atThreshold.recommendations().stream()
                .map(recommendation -> recommendation.id()).toList());
    }

    @Test
    void semanticAdmissionRequiresProfessionalEvidenceAndValidRawSemanticScore() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User companyOnly = mock(User.class);
        when(companyOnly.getId()).thenReturn(62L);
        when(companyOnly.getName()).thenReturn("Cory Mentor");
        when(companyOnly.getCompany()).thenReturn("Quantum Computing Labs");
        User professionalButUnrelated = alumni(63L, "Rae Mentor", "wedding photography", "Photographer");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(companyOnly), List.of(professionalButUnrelated),
                        List.of(professionalButUnrelated));
        when(userSkills.findByUser_IdIn(List.of(62L))).thenReturn(List.of());
        when(userSkills.findByUser_IdIn(List.of(63L))).thenReturn(List.of());
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "rankingStrategy", "semantic");
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:8090");
        doReturn(ResponseEntity.ok(Map.of("rankedIds", List.of(62L), "scores", Map.of("62", 0.9),
                        "semanticScores", Map.of("62", 0.99))))
                .doReturn(ResponseEntity.ok(Map.of("rankedIds", List.of(63L), "scores", Map.of("63", 0.9))))
                .doReturn(ResponseEntity.ok(Map.of("rankedIds", List.of(63L), "scores", Map.of("63", 0.9),
                        "semanticScores", Map.of("63", Double.NaN))))
                .when(restTemplate).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                        any(ParameterizedTypeReference.class), any(Object[].class));

        var companyOnlyResponse = service.discover("How can I start in quantum computing?");
        var missingScoreResponse = service.discover("How can I start in quantum computing?");
        var malformedScoreResponse = service.discover("How can I start in quantum computing?");

        assertTrue(companyOnlyResponse.recommendations().isEmpty(),
                "a company-name overlap cannot prove professional expertise");
        assertTrue(missingScoreResponse.recommendations().isEmpty(),
                "semantic-only retrieval must abstain when the raw score is absent");
        assertTrue(malformedScoreResponse.recommendations().isEmpty(),
                "non-finite raw semantic scores must be rejected");
    }

    @Test
    void configuredMinimumRelevanceCanRequireMoreThanOneMatchedTerm() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User weak = alumni(21L, "Sam Mentor", "data analytics", "Data Analyst");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
                .thenReturn(List.of(relevant));
        when(userSkills.findByUser_IdIn(List.of(21L))).thenReturn(List.of());

        for (String goal : List.of("I want to work in AI. What should I learn?",
                "How can I get into deep learning?")) {
            var response = service.discover(goal);
            assertEquals(List.of(21L), response.recommendations().stream()
                    .map(recommendation -> recommendation.id()).toList(), goal);
            assertTrue(response.recommendations().getFirst().relevanceEstimate() > 0, goal);
        }
    }

    @Test
    void nonIntegralRankingIdIsRejectedAndFallsBackSafely() {
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);
        User relevant = alumni(21L, "Sam Mentor", "data engineering", "Data Engineer");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc("ALUMNI", "APPROVED", 0L))
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
