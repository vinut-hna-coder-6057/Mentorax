package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import com.alumni.alumni_connect.service.MentorDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in integration check against a separately running local mentorax-ai service. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.ai.enabled=true")
@EnabledIfSystemProperty(named = "mentorax.ai.e2e", matches = "true")
class MentorDiscoveryE2ETest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MentorDiscoveryService discoveryService;

    @Test
    void studentRequestUsesLocalRankerThenFallsBackWhenItIsUnavailable() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User student = new User("Synthetic Student", "e2e-student-" + suffix + "@example.test",
                "not-used", "STUDENT", "APPROVED");
        student.setEmailVerified(true);
        student = users.save(student);
        User mentor = new User("Synthetic Data Mentor", "e2e-mentor-" + suffix + "@example.test",
                "not-used", "ALUMNI", "APPROVED");
        mentor.setJobRole("Data Engineer");
        mentor.setCompany("Example Analytics");
        mentor.setSkills("Python, data engineering");
        mentor.setBio("Builds data pipelines.");
        mentor = users.save(mentor);
        String token = "Bearer " + jwtUtil.generateToken(student.getEmail(), "STUDENT");

        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"How can I prepare for data engineering with Python?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.rankingMethod").value(
                        "TF-IDF ordering with Java profile evidence gate"))
                .andExpect(jsonPath("$.recommendations[0].id").value(mentor.getId()))
                .andExpect(jsonPath("$.recommendations[0].relevanceEstimate").isNumber())
                .andExpect(jsonPath("$.recommendations[0].evidence").isArray())
                .andExpect(jsonPath("$.recommendations[0].conversationTopic").isNotEmpty())
                .andExpect(jsonPath("$.recommendations[0].messageDraft").isNotEmpty());

        ReflectionTestUtils.setField(discoveryService, "aiServiceUrl", "http://127.0.0.1:1");
        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"How can I prepare for data engineering with Python?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackUsed").value(true))
                .andExpect(jsonPath("$.recommendations[0].id").value(mentor.getId()));
    }
}
