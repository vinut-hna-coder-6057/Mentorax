package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import com.alumni.alumni_connect.service.EmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alumni.alumni_connect.entity.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.alumni.alumni_connect.entity.Notification;
import com.alumni.alumni_connect.repository.ConversationRepository;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.NotificationRepository;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
private MessageRepository messageRepository;

@Autowired
private ConversationRepository conversationRepository;
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
void duplicateEventRegistrationIsHandledGracefully() throws Exception {

    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Duplicate Registration Test",
                              "description": "Testing duplicate registration",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    // First registration
    mockMvc.perform(
            post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Registered successfully"));

    // Second registration
    mockMvc.perform(
            post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Already registered"));
}
@Test
void unauthenticatedUserCannotRegisterForEvent() throws Exception {

    mockMvc.perform(
            post("/events/register")
                    .param("eventId", "999999")
    )
    .andExpect(status().isUnauthorized());
}
@Test
void userCannotChangeOwnRoleOrApprovalStatusThroughProfileUpdate() throws Exception {

    mockMvc.perform(
            put("/users/{id}", student.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                            "name": "Updated Student",
                            "role": "ADMIN",
                            "status": "APPROVED"
                        }
                    """)
        )
        .andExpect(status().isOk());

    User updated = users.findById(student.getId()).orElseThrow();

    org.junit.jupiter.api.Assertions.assertEquals("STUDENT", updated.getRole());
    org.junit.jupiter.api.Assertions.assertEquals("APPROVED", updated.getStatus());
    org.junit.jupiter.api.Assertions.assertEquals("Updated Student", updated.getName());
}
@Test
void eventCreatorCanViewAttendees() throws Exception {

    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Attendee Access Test",
                              "description": "Testing attendee access",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    mockMvc.perform(
            get("/events/attendees/{eventId}", eventId)
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk());
}
@Test
void nonCreatorCannotViewEventAttendees() throws Exception {

    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Private Attendee Test",
                              "description": "Testing attendee authorization",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    mockMvc.perform(
            get("/events/attendees/{eventId}", eventId)
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isForbidden());
}
@Test
void registrationCreatesCorrectAttendeeRecord() throws Exception {

    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Registration Integrity Test",
                              "description": "Testing registration integrity",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    // Register student
    mockMvc.perform(
            post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Registered successfully"));

    // Admin can retrieve attendees
    mockMvc.perform(
            get("/events/attendees/{eventId}", eventId)
                    .header(
                            "Authorization",
                            bearer(admin.getEmail(), "ADMIN")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].eventId").value(eventId))
            .andExpect(jsonPath("$[0].studentEmail")
                    .value(student.getEmail()));
}
@Test
void unauthenticatedUserCannotRetrieveConversation() throws Exception {

    mockMvc.perform(
            get("/messages/conversation")
                    .param("sender", "alice@example.com")
                    .param("receiver", "bob@example.com")
    )
    .andExpect(status().isUnauthorized());
}

@Test
void studentCanCancelEventRegistrationAndAttendeeCountDecreases() throws Exception {

    // Create approved event
    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Cancellation Test Event",
                              "description": "Testing event cancellation",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    // Register student
    mockMvc.perform(
            post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Registered successfully"));

    // Verify attendee count became 1
    mockMvc.perform(
            get("/events/all")
                    .header(
                            "Authorization",
                            bearer(admin.getEmail(), "ADMIN")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].attendeeCount").value(1));

    // Cancel registration
    mockMvc.perform(
            delete("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Registration cancelled"));

    // Verify attendee count returned to 0
    mockMvc.perform(
            get("/events/all")
                    .header(
                            "Authorization",
                            bearer(admin.getEmail(), "ADMIN")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].attendeeCount").value(0));
}
@Test
void cancellingUnregisteredEventReturnsNotRegistered() throws Exception {

    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Unregistered Cancellation Test",
                              "description": "Testing cancellation without registration",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    mockMvc.perform(
            delete("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Not registered"));
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
void authenticatedUserCanGetNotifications() throws Exception {
    mockMvc.perform(get("/notifications")
            .header("Authorization", bearer(student.getEmail(), "STUDENT")))
        .andExpect(status().isOk());
}
@Test
void authenticatedUserCanGetUnreadNotificationCount() throws Exception {
    mockMvc.perform(get("/notifications/unread")
            .header("Authorization", bearer(student.getEmail(), "STUDENT")))
        .andExpect(status().isOk());
}
@Test
void markingNonexistentNotificationReturns404() throws Exception {
    mockMvc.perform(put("/notifications/read/{id}", 999999L)
            .header("Authorization", bearer(student.getEmail(), "STUDENT")))
        .andExpect(status().isNotFound());
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
void authenticatedUserCanGetOwnProfile() throws Exception {
    mockMvc.perform(get("/users/{id}", student.getId())
            .header("Authorization", bearer(student.getEmail(), "STUDENT")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(student.getEmail()))
        .andExpect(jsonPath("$.password").doesNotExist());
}
@Test
void unauthenticatedUserCannotGetProfile() throws Exception {
    mockMvc.perform(get("/users/{id}", student.getId()))
        .andExpect(status().isUnauthorized());
}
@Test
void userCanUpdateOwnProfile() throws Exception {
    mockMvc.perform(put("/users/{id}", student.getId())
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "fullName": "Updated Student"
                }
                """))
        .andExpect(status().isOk());
}
@Test
void userCannotUpdateAnotherUsersProfile() throws Exception {
    mockMvc.perform(put("/users/{id}", admin.getId())
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "fullName": "Unauthorized Update"
                }
                """))
        .andExpect(status().isForbidden());
}
@Test
void adminCanUpdateAnotherUsersProfile() throws Exception {
    mockMvc.perform(put("/users/{id}", student.getId())
            .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "fullName": "Admin Updated Student"
                }
                """))
     .andExpect(status().isOk());
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
void eventCreatorCanUpdateOwnEvent() throws Exception {
    String response = mockMvc.perform(post("/events")
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Original Event",
                    "description": "Original description",
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

    mockMvc.perform(put("/events/{id}", eventId)
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Updated Event",
                    "description": "Updated description",
                    "location": "Updated Location"
                }
                """))
        .andExpect(status().isOk());
}
@Test
void adminCanUpdateAnotherUsersEvent() throws Exception {
    String response = mockMvc.perform(post("/events")
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Student Event",
                    "description": "Student event",
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

    mockMvc.perform(put("/events/{id}", eventId)
            .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Unauthorized Update",
                    "description": "Should not update",
                    "location": "Other Location"
                }
                """))
        .andExpect(status().isOk());
}
@Test
void eventCreatorCanDeleteOwnEvent() throws Exception {
    String response = mockMvc.perform(post("/events")
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Delete Test Event",
                    "description": "Event to delete",
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

    mockMvc.perform(delete("/events/{id}", eventId)
            .header("Authorization", bearer(student.getEmail(), "STUDENT")))
        .andExpect(status().isOk());
}
@Test
 void adminCanDeleteAnotherUsersEvent() throws Exception {
    String response = mockMvc.perform(post("/events")
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "title": "Protected Event",
                    "description": "Protected event",
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

    mockMvc.perform(delete("/events/{id}", eventId)
            .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
        .andExpect(status().isOk());
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
void authenticatedStudentCanRegisterForEvent() throws Exception {

    // Create an event as admin so it is automatically APPROVED
    String eventResponse = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Registration Test Event",
                              "description": "Event registration integration test",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long eventId = new ObjectMapper()
            .readTree(eventResponse)
            .get("id")
            .asLong();

    // Register as student
    mockMvc.perform(
            post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header(
                            "Authorization",
                            bearer(student.getEmail(), "STUDENT")
                    ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message")
                    .value("Registered successfully"));
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
