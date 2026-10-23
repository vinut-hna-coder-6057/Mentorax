package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opt-in end-to-end test; requires local `uvicorn inbox_app:app` and its test bearer token. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "AI_INBOX_ASSISTANT_ENABLED=true",
        "INBOX_ASSISTANT_API_TOKEN=test-only-inbox-token",
        "app.ai.service-url=http://127.0.0.1:8090",
        "logging.level.org.springframework.web.client.RestTemplate=WARN"
})
@EnabledIfSystemProperty(named = "mentorax.ai.inbox-e2e", matches = "true")
class InboxAssistantE2ETest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private MessageRepository messages;
    @Autowired private JwtUtil jwtUtil;

    @Test
    void authorizedAlumniMessageIsAnalyzedWithoutPersistingAssistantOutput() throws Exception {
        String suffix = UUID.randomUUID().toString();
        User alumni = new User("Synthetic Alumni", "assistant-alumni-" + suffix + "@example.test",
                "not-used", "ALUMNI", "APPROVED");
        alumni.setEmailVerified(true);
        alumni = users.save(alumni);
        User student = new User("Synthetic Student", "assistant-student-" + suffix + "@example.test",
                "not-used", "STUDENT", "APPROVED");
        student.setEmailVerified(true);
        student = users.save(student);

        Message incoming = new Message(student.getEmail(), alumni.getEmail(),
                "Could you help me prepare for my technical interview?", LocalDateTime.now());
        incoming = messages.save(incoming);
        long messageId = incoming.getId();
        String token = "Bearer " + jwtUtil.generateToken(alumni.getEmail(), "ALUMNI");

        mockMvc.perform(post("/messages/{messageId}/assistant", messageId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.category").value("INTERVIEW_PREPARATION"))
                .andExpect(jsonPath("$.summary").value(incoming.getContent()))
                .andExpect(jsonPath("$.draftKind").value("deterministic_template"));

        org.junit.jupiter.api.Assertions.assertEquals(1, messages.count(),
                "assistant output must not create or mutate message records");
    }
}
