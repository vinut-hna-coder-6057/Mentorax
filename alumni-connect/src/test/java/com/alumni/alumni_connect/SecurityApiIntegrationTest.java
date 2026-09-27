package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import com.alumni.alumni_connect.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.alumni.alumni_connect.entity.Notification;
import com.alumni.alumni_connect.repository.NotificationRepository;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.SimpleMailMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import org.springframework.security.crypto.password.PasswordEncoder;
@SpringBootTest
@AutoConfigureMockMvc
@Transactional

class SecurityApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;
    @Autowired
private NotificationRepository notificationRepository;
@Autowired
private EmailService emailService;
    private User student;
    private User admin;
@MockBean
private JavaMailSender mailSender;
@Autowired
private PasswordEncoder passwordEncoder;
    @BeforeEach
    void setUpUsers() {
        String suffix = UUID.randomUUID().toString();
        student = users.save(new User("Security Student", "security-student-" + suffix + "@example.test",
                "not-used", "STUDENT", "APPROVED"));
        admin = users.save(new User("Security Admin", "security-admin-" + suffix + "@example.test",
                "not-used", "ADMIN", "APPROVED"));
    }

    @Test
    void protectedEndpointWithoutAuthenticationReturnsJson401() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"Authentication required\"}"));
    }
    @Test
void userCanMarkOwnNotificationAsRead() throws Exception {
    Notification notification = new Notification();
    notification.setRecipient(student);
    notification.setMessage("Test notification");
    notification.setType("TEST");
    notification.setLinkUrl("/test");

    notification = notificationRepository.save(notification);

    mockMvc.perform(
            put("/notifications/read/{id}", notification.getId())
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    )
    )
    .andExpect(status().isOk());
}
@Test
void completePasswordResetFlowSucceeds() throws Exception {
    String email = student.getEmail();
    String oldPassword = "Password123";
    String newPassword = "NewPassword123";

    student.setPassword(passwordEncoder.encode(oldPassword));
    student.setEmailVerified(true);
    users.save(student);

    final String[] capturedOtp = new String[1];

    doAnswer(invocation -> {
        SimpleMailMessage message = invocation.getArgument(0);
        String body = message.getText();

        // OTP is between these two markers
        String marker = "Your OTP for Alumni Connect is:\n\n";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf("\n\n", start);

        capturedOtp[0] = body.substring(start, end).trim();

        return null;
    }).when(mailSender).send(any(SimpleMailMessage.class));

    // 1. Request password reset
    mockMvc.perform(post("/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s"
                }
                """.formatted(email)))
            .andExpect(status().isOk());

    // Make sure an OTP was actually generated and sent
    org.junit.jupiter.api.Assertions.assertNotNull(capturedOtp[0]);
    org.junit.jupiter.api.Assertions.assertTrue(
            capturedOtp[0].matches("\\d{6}")
    );

    // 2. Verify OTP
    mockMvc.perform(post("/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "otp": "%s"
                }
                """.formatted(email, capturedOtp[0])))
            .andExpect(status().isOk());

    // 3. Reset password
    mockMvc.perform(post("/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "newPassword": "%s"
                }
                """.formatted(email, newPassword)))
            .andExpect(status().isOk());

    // 4. Login using the NEW password
    mockMvc.perform(post("/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, newPassword)))
            .andExpect(status().isOk())
            .andExpect(content().string(
                    org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.emptyString()
                    )
            ));
}
@Test
void userCannotMarkAnotherUsersNotificationAsRead() throws Exception {
    Notification notification = new Notification();
    notification.setRecipient(admin);
    notification.setMessage("Admin notification");
    notification.setType("TEST");
    notification.setLinkUrl("/test");

    notification = notificationRepository.save(notification);

    mockMvc.perform(
            put("/notifications/read/{id}", notification.getId())
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    )
    )
    .andExpect(status().isForbidden());
}
    @Test
    void nonAdminCannotAccessAdminListing() throws Exception {
        mockMvc.perform(get("/users")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"Access denied\"}"));
    }

    @Test
    void adminCanAccessAdminListing() throws Exception {
        mockMvc.perform(get("/users")
                        .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
                .andExpect(status().isOk());
    }
    @Test
void unauthenticatedUserCannotAccessStudents() throws Exception {
    mockMvc.perform(get("/users/students"))
            .andExpect(status().isUnauthorized());
}

@Test
void unauthenticatedUserCannotAccessAlumni() throws Exception {
    mockMvc.perform(get("/users/alumni"))
            .andExpect(status().isUnauthorized());
}
    @Test
    void missingUserReturns404() throws Exception {
        mockMvc.perform(get("/users/{id}", Long.MAX_VALUE)
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"User not found\"}"));
    }

    private String bearer(String email, String role) {
        return "Bearer " + jwtUtil.generateToken(email, role);
    }
    @Test
void userCannotSendConnectionRequestToSelf() throws Exception {
    mockMvc.perform(post("/connections/{receiverId}", student.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isBadRequest());
}
@Test
void duplicateConnectionRequestIsRejected() throws Exception {
    mockMvc.perform(post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            
                    .andExpect(status().isOk());
    mockMvc.perform(post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isConflict());
}
@Test
void forgotPasswordForExistingUserReturnsGenericResponse() throws Exception {
    mockMvc.perform(post("/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "student@example.com"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(content().string("If an account exists, an OTP has been sent"));
}

@Test
void forgotPasswordForUnknownUserReturnsGenericResponse() throws Exception {
    mockMvc.perform(post("/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "unknown@example.com"
                }
                """))
            .andExpect(status().isOk())
            .andExpect(content().string("If an account exists, an OTP has been sent"));
}

@Test
void invalidPasswordResetOtpIsRejected() throws Exception {
    mockMvc.perform(post("/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "student@example.com",
                  "otp": "123456"
                }
                """))
            .andExpect(status().isBadRequest());
}

@Test
void resetPasswordWithoutVerifiedOtpIsRejected() throws Exception {
    mockMvc.perform(post("/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "student@example.com",
                  "newPassword": "NewPassword123"
                }
                """))
            .andExpect(status().isBadRequest());
}
@Test
void receiverCanAcceptConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization",
                            bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isOk());

}
@Test
void requesterCannotAcceptOwnConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isForbidden());
}
@Test
void receiverCanRejectConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization",
                            bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "REJECTED"))
            .andExpect(status().isOk());
}
@Test
void adminCanApproveEvent() throws Exception {
    String response = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "title": "Approval Test Event",
                          "description": "Event for approval testing",
                          "location": "College Campus"
                        }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(
            put("/events/approve/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());
}
@Test
void adminCanRejectEvent() throws Exception {
    String response = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "title": "Rejection Test Event",
                          "description": "Event for rejection testing",
                          "location": "College Campus"
                        }
                    """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(
            put("/events/reject/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());
}
@Test
void authenticatedUserCanGetConnections() throws Exception {
    mockMvc.perform(get("/connections")
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk());
}
@Test
void authenticatedUserCanCreateEvent() throws Exception {
    mockMvc.perform(post("/events")
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Test Event",
                              "description": "Security API test event",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk());
}
@Test
void adminCanAccessAllEvents() throws Exception {
    mockMvc.perform(get("/events/all")
                    .header("Authorization",
                            bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());
}

}
