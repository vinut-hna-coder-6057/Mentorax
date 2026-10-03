package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.EmailOutboxRepository;
import com.alumni.alumni_connect.repository.OtpRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.service.EmailOutboxDispatcher;
import com.alumni.alumni_connect.service.EmailOutboxService;
import com.alumni.alumni_connect.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.email-verification.required=false",
        "app.email-outbox.enabled=true",
        "app.email-outbox.poll-delay-ms=60000"
})
@AutoConfigureMockMvc
@Transactional
class EmailVerificationDisabledIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private OtpRepository otps;
    @Autowired private EmailOutboxRepository outbox;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmailOutboxService emailOutboxService;
    @Autowired private EmailOutboxDispatcher emailOutboxDispatcher;
    @MockBean private EmailService emailService;

    @Test
    void signupDoesNotQueueOtpAndUnverifiedStudentCanSignIn() throws Exception {
        String email = "otp-disabled-" + UUID.randomUUID() + "@example.test";
        String password = "StudentPassword123";

        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "OTP Disabled Student",
                                "email", email,
                                "password", password,
                                "role", "STUDENT",
                                "emailVerified", true))))
                .andExpect(status().isOk())
                .andExpect(content().string("Signup successful"));

        User created = users.findByEmailIgnoreCase(email).orElseThrow();
        assertFalse(created.isEmailVerified());
        assertTrue(passwordEncoder.matches(password, created.getPassword()));
        assertTrue(otps.findFirstByEmailAndPurposeOrderByIdDesc(
                email, "EMAIL_VERIFICATION").isEmpty());
        assertTrue(outbox.findFirstByRecipientOrderByIdDesc(email).isEmpty());

        mockMvc.perform(post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "OTP Disabled Student",
                                "email", email,
                                "password", password,
                                "role", "STUDENT"))))
                .andExpect(status().isOk());
        assertTrue(users.findByEmailIgnoreCase(email).isPresent());
        assertTrue(otps.findFirstByEmailAndPurposeOrderByIdDesc(
                email, "EMAIL_VERIFICATION").isEmpty());
        mockMvc.perform(post("/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted())
                .andExpect(content().string("Email verification is temporarily disabled."));
        assertTrue(otps.findFirstByEmailAndPurposeOrderByIdDesc(
                email, "EMAIL_VERIFICATION").isEmpty());
        assertTrue(outbox.findFirstByRecipientOrderByIdDesc(email).isEmpty());

        emailOutboxService.enqueueVerification(email, "123456");
        emailOutboxDispatcher.dispatchNext();
        assertTrue(outbox.findFirstByRecipientOrderByIdDesc(email).isEmpty());
        verifyNoInteractions(emailService);

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(email, password, "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(".")));

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(email, "WrongPassword123", "STUDENT")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid credentials"));

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(email, password, "ALUMNI")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("Invalid email, password, or account type"));
    }

    @Test
    void disabledEmailVerificationDoesNotBypassAccountStatusRestrictions() throws Exception {
        User pendingAlumni = users.save(new User(
                "Pending Alumni",
                "pending-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("AlumniPassword123"),
                "ALUMNI",
                "PENDING"));
        User rejectedStudent = users.save(new User(
                "Rejected Student",
                "rejected-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("StudentPassword123"),
                "STUDENT",
                "REJECTED"));
        User blockedStudent = users.save(new User(
                "Blocked Student",
                "blocked-" + UUID.randomUUID() + "@example.test",
                passwordEncoder.encode("StudentPassword123"),
                "STUDENT",
                "BLOCKED"));

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(pendingAlumni.getEmail(), "AlumniPassword123", "ALUMNI")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Account pending approval"));

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(rejectedStudent.getEmail(), "StudentPassword123", "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Account rejected"));

        mockMvc.perform(post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(login(blockedStudent.getEmail(), "StudentPassword123", "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Account not active"));
    }

    private String login(String email, String password, String role) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", password,
                "role", role));
    }
}
