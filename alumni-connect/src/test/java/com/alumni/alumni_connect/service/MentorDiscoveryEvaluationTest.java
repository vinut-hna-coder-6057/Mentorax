package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryRequest;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.repository.UserSkillRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MentorDiscoveryEvaluationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void evaluatesSyntheticCorpusThroughRealJavaClassifierAndFallback() throws Exception {
        Path datasetPath = Path.of(System.getProperty("mentorax.evaluation.dataset",
                "../mentorax-ai/evaluation/dataset.json")).toAbsolutePath();
        Path outputPath = Path.of(System.getProperty("mentorax.evaluation.output",
                "target/mentorax-evaluation-java.json")).toAbsolutePath();
        JsonNode dataset = mapper.readTree(datasetPath.toFile());

        UserRepository users = mock(UserRepository.class);
        UserSkillRepository skills = mock(UserSkillRepository.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);

        List<User> alumni = new ArrayList<>();
        for (JsonNode profile : dataset.path("profiles")) {
            User user = mock(User.class);
            when(user.getId()).thenReturn(profile.path("id").asLong());
            when(user.getName()).thenReturn("Synthetic Alumni " + profile.path("id").asText());
            when(user.getRole()).thenReturn("ALUMNI");
            when(user.getJobRole()).thenReturn(profile.path("jobTitle").asText());
            when(user.getCompany()).thenReturn(profile.path("company").asText());
            when(user.getBio()).thenReturn(profile.path("bio").asText());
            when(user.getInterests()).thenReturn(profile.path("interests").asText());
            List<String> profileSkills = new ArrayList<>();
            profile.path("skills").forEach(skill -> profileSkills.add(skill.asText()));
            when(user.getSkills()).thenReturn(String.join(",", profileSkills));
            alumni.add(user);
        }
        when(users.findByRoleAndStatus("ALUMNI", "APPROVED", PageRequest.of(0, 500))).thenReturn(alumni);
        when(skills.findByUser_IdIn(anyList())).thenReturn(List.of());

        MentorDiscoveryService discovery = new MentorDiscoveryService(users, skills, currentUser, restTemplate);
        ReflectionTestUtils.setField(discovery, "aiEnabled", false);
        ReflectionTestUtils.setField(discovery, "aiServiceUrl", "http://127.0.0.1:8090");
        CareerAssistantService assistant = new CareerAssistantService(new QuestionIntentClassifier(), discovery,
                new GeneralQuestionAnswerService());
        var validator = Validation.buildDefaultValidatorFactory().getValidator();

        ArrayNode results = evaluateCases(dataset, assistant, validator, false);
        verify(restTemplate, never()).exchange(anyString(), any(), any(), any(org.springframework.core.ParameterizedTypeReference.class), any(Object[].class));
        ReflectionTestUtils.setField(discovery, "aiEnabled", true);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class)))
                .thenThrow(new ResourceAccessException("synthetic Python ranker outage"));
        ArrayNode unavailableResults = evaluateCases(dataset, assistant, validator, true);
        verify(restTemplate, atLeastOnce()).exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class), any(Object[].class));
        Files.createDirectories(outputPath.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), Map.of(
                "mode", "java_fallback", "implementation", "CareerAssistantService + QuestionIntentClassifier + MentorDiscoveryService",
                "cases", results, "pythonUnavailableCases", unavailableResults));
    }

    private ArrayNode evaluateCases(JsonNode dataset, CareerAssistantService assistant,
                                    jakarta.validation.Validator validator, boolean pythonUnavailable) {
        ArrayNode results = mapper.createArrayNode();
        for (JsonNode item : dataset.path("questions")) {
            String question = question(item.path("question"));
            String expectedIntent = item.path("expectedIntent").asText();
            String predictedIntent;
            List<Long> recommendations;
            String rankingMethod = "No alumni ranking performed";
            boolean fallbackUsed = false;
            if (expectedIntent.equals("VALIDATION_ERROR") || !validator.validate(new MentorDiscoveryRequest(question)).isEmpty()) {
                predictedIntent = "VALIDATION_ERROR";
                recommendations = List.of();
            } else {
                var first = assistant.respond(question);
                var second = assistant.respond(question);
                predictedIntent = first.intent().name();
                recommendations = first.recommendations().stream().map(row -> row.id()).toList();
                rankingMethod = first.rankingMethod();
                fallbackUsed = first.fallbackUsed();
                if (pythonUnavailable && !recommendations.isEmpty()) {
                    assertTrue(fallbackUsed, item.path("id").asText());
                    assertEquals("Java keyword overlap with profile evidence gate", rankingMethod, item.path("id").asText());
                }
                assertEquals(predictedIntent, second.intent().name(), item.path("id").asText());
                assertEquals(recommendations, second.recommendations().stream().map(row -> row.id()).toList(), item.path("id").asText());
            }
            var result = mapper.createObjectNode();
            result.put("id", item.path("id").asText());
            result.put("expectedIntent", expectedIntent);
            result.put("predictedIntent", predictedIntent);
            result.put("expectedRecommendations", item.path("expectedRecommendations").asBoolean());
            result.set("recommendedIds", mapper.valueToTree(recommendations));
            result.put("rankingMethod", rankingMethod);
            result.put("fallbackUsed", fallbackUsed);
            result.put("deterministic", true);
            results.add(result);
        }
        return results;
    }

    private String question(JsonNode node) {
        if (node.isTextual()) return node.asText();
        return node.path("repeat").asText().repeat(node.path("count").asInt());
    }
}
