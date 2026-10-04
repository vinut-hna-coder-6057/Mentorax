package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.entity.EmailOutboxMessage;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.repository.EmailOutboxRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import com.alumni.alumni_connect.service.EmailService;
import com.alumni.alumni_connect.service.EmailOutboxCipher;
import com.alumni.alumni_connect.service.EmailOutboxDispatcher;
import com.alumni.alumni_connect.service.EmailOutboxService;
import com.alumni.alumni_connect.service.OtpService;
import com.alumni.alumni_connect.service.RequestRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alumni.alumni_connect.entity.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import com.alumni.alumni_connect.entity.Notification;
import com.alumni.alumni_connect.entity.Otp;
import com.alumni.alumni_connect.repository.ConversationRepository;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.NotificationRepository;
import com.alumni.alumni_connect.repository.OtpRepository;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.EventRepository;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.any;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
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
@MockBean
private EmailService emailService;
    private User student;
    private User alumni;
    private User admin;
@Autowired
private PasswordEncoder passwordEncoder;
@Autowired
private OtpRepository otpRepository;
@Autowired
private AlumniProfileRepository alumniProfileRepository;
@Autowired
private StudentProfileRepository studentProfileRepository;
@Autowired
private EventRepository eventRepository;
@Autowired
private PlatformTransactionManager transactionManager;
@Autowired
private RequestRateLimiter rateLimiter;
@Autowired
private OtpService otpService;
@Autowired
private EmailOutboxRepository emailOutboxRepository;
@Autowired
private EmailOutboxService emailOutboxService;
@Autowired
private EmailOutboxCipher emailOutboxCipher;
    @BeforeEach
    void setUpUsers() {
        emailOutboxRepository.deleteAllInBatch();
        ((java.util.Map<?, ?>) ReflectionTestUtils.getField(rateLimiter, "buckets")).clear();
        String suffix = UUID.randomUUID().toString();
        student = users.save(new User("Security Student", "security-student-" + suffix + "@example.test",
                "not-used", "STUDENT", "APPROVED"));
        alumni = users.save(new User("Security Alumni", "security-alumni-" + suffix + "@example.test",
                "not-used", "ALUMNI", "APPROVED"));
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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

    assertTrue(notificationRepository.findById(notification.getId()).orElseThrow().isRead());
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
    verify(emailService, times(1)).sendEventRegistrationEmail(
            eq(student.getEmail()),
            eq("Duplicate Registration Test"),
            nullable(String.class),
            eq("College Campus"),
            nullable(String.class));
}
@Test
void alumniEndpointWithoutAuthenticationReturns401() throws Exception {
    mockMvc.perform(get("/alumni"))
            .andExpect(status().isUnauthorized());
}

@Test
void approvedAlumniEndpointWithoutAuthenticationReturns401() throws Exception {
    mockMvc.perform(get("/alumni/approved"))
            .andExpect(status().isUnauthorized());
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
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
                            bearer(alumni.getEmail(), "ALUMNI")
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
void pendingAndRejectedEventsCannotAcceptRegistrations() throws Exception {
    String eventResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Pending event\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    long eventId = new ObjectMapper().readTree(eventResponse).get("id").asLong();

    mockMvc.perform(post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isConflict());

    mockMvc.perform(put("/events/reject/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());

    mockMvc.perform(post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isConflict());

    assertEquals(0, eventRepository.findById(eventId).orElseThrow().getAttendeeCount());
}

@Test
void eventApprovalAndRejectionOnlyTransitionPendingEvents() throws Exception {
    String eventResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Pending approval\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long eventId = new ObjectMapper().readTree(eventResponse).get("id").asLong();

    mockMvc.perform(put("/events/approve/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));
    mockMvc.perform(put("/events/approve/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());
    mockMvc.perform(put("/events/reject/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());

    String secondResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Pending rejection\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long rejectedEventId = new ObjectMapper().readTree(secondResponse).get("id").asLong();
    mockMvc.perform(put("/events/reject/{id}", rejectedEventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));
    mockMvc.perform(put("/events/reject/{id}", rejectedEventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());
    mockMvc.perform(put("/events/approve/{id}", rejectedEventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());
}

@Test
void alumniCanSeeOwnPendingEventAndAdminCanSeeItInManagementList() throws Exception {
    String eventResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"My pending event\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long eventId = new ObjectMapper().readTree(eventResponse).get("id").asLong();

    mockMvc.perform(get("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")].status")
                    .value(org.hamcrest.Matchers.contains("PENDING")));
    mockMvc.perform(get("/events")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")]")
                    .value(org.hamcrest.Matchers.hasSize(0)));
    mockMvc.perform(get("/events/all")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")].status")
                    .value(org.hamcrest.Matchers.contains("PENDING")));
}

@Test
void anonymousUsersCanSeeApprovedEventsWithoutSeeingPendingEvents() throws Exception {
    String pendingResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Private pending event\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long pendingEventId = new ObjectMapper().readTree(pendingResponse).get("id").asLong();

    String approvedResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Public approved event\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long approvedEventId = new ObjectMapper().readTree(approvedResponse).get("id").asLong();

    mockMvc.perform(get("/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + pendingEventId + ")]")
                    .value(org.hamcrest.Matchers.hasSize(0)))
            .andExpect(jsonPath("$[?(@.id == " + approvedEventId + ")].status")
                    .value(org.hamcrest.Matchers.contains("APPROVED")));
}

@Test
void eventCreationRejectsBlankRequiredTitle() throws Exception {
    mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"  \"}"))
            .andExpect(status().isBadRequest());
}

@Test
void alumniCanRegisterAndRegistrationStatusTracksCancellation() throws Exception {
    String eventResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Approved event\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long eventId = new ObjectMapper().readTree(eventResponse).get("id").asLong();

    mockMvc.perform(post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Registered successfully"));
    mockMvc.perform(get("/events/{eventId}/registration-status", eventId)
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registered").value(true));
    assertEquals(1, eventRepository.findById(eventId).orElseThrow().getAttendeeCount());

    mockMvc.perform(delete("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Registration cancelled"));
    mockMvc.perform(get("/events/{eventId}/registration-status", eventId)
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.registered").value(false));
    assertEquals(0, eventRepository.findById(eventId).orElseThrow().getAttendeeCount());
}

@Test
void eventWithRegistrationsCannotBeDeletedOrLoseRegistrationData() throws Exception {
    String eventResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Registered event\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long eventId = new ObjectMapper().readTree(eventResponse).get("id").asLong();
    mockMvc.perform(post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk());

    mockMvc.perform(delete("/events/{id}", eventId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());

    assertTrue(eventRepository.existsById(eventId));
    assertTrue(eventRepository.findById(eventId).orElseThrow().getAttendeeCount() == 1);
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
        capturedOtp[0] = invocation.getArgument(1);
        return null;
    }).when(emailService).sendOtpEmail(anyString(), anyString());

    // 1. Request password reset
    mockMvc.perform(post("/forgot-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "  %s  "
                }
                """.formatted(email.toUpperCase(java.util.Locale.ROOT))))
            .andExpect(status().isOk());

    new EmailOutboxDispatcher(emailOutboxService, emailOutboxCipher, emailService)
            .dispatchNext();

    // Make sure an OTP was generated and sent to the requested address.
    org.junit.jupiter.api.Assertions.assertNotNull(capturedOtp[0]);
    org.junit.jupiter.api.Assertions.assertTrue(
            capturedOtp[0].matches("\\d{6}")
    );

    // 2. Verify OTP
    String verificationResponse = mockMvc.perform(post("/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "otp": "%s"
                }
                """.formatted(email, capturedOtp[0])))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String resetToken = new ObjectMapper().readTree(verificationResponse)
            .get("resetToken").asText();

    // 3. Reset password
    mockMvc.perform(post("/reset-password")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "email": "%s",
                  "newPassword": "%s",
                  "resetToken": "%s"
                }
                """.formatted(email, newPassword, resetToken)))
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
void nonAdminCannotLookUpUnapprovedProfileByIdOrEmail() throws Exception {
    alumni.setStatus("PENDING");
    users.save(alumni);

    mockMvc.perform(get("/users/{id}", alumni.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isNotFound());
    mockMvc.perform(get("/users/email/{email}", alumni.getEmail())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isNotFound());

    mockMvc.perform(get("/users/{id}", alumni.getId())
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
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
                    "name": "Updated Student"
                }
                """))
        .andExpect(status().isOk());
    assertEquals("Updated Student", users.findById(student.getId()).orElseThrow().getName());
}

@Test
void profileUpdateRejectsBlankNameWithoutChangingExistingName() throws Exception {
    mockMvc.perform(put("/users/{id}", student.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"   "}
                            """))
            .andExpect(status().isBadRequest());

    assertEquals("Security Student", users.findById(student.getId()).orElseThrow().getName());
}
@Test
void userCannotUpdateAnotherUsersProfile() throws Exception {
    mockMvc.perform(put("/users/{id}", admin.getId())
            .header("Authorization", bearer(student.getEmail(), "STUDENT"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                    "name": "Unauthorized Update"
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
                    "name": "Admin Updated Student"
                }
                """))
     .andExpect(status().isOk())
     .andExpect(jsonPath("$.name").value("Admin Updated Student"));
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
            .andExpect(content().string("If an account exists, a code will be sent shortly"));
}
@Test
void eventCreatorCanUpdateOwnEvent() throws Exception {
    String response = mockMvc.perform(post("/events")
            .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
            .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
            .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
            .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
            .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
        .andExpect(status().isOk());
}
@Test
 void adminCanDeleteAnotherUsersEvent() throws Exception {
    String response = mockMvc.perform(post("/events")
            .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
            .andExpect(content().string("If an account exists, a code will be sent shortly"));
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
void signupCreatesRoleSpecificProfilesAndAccountStatusIsAuthoritative() throws Exception {
    String studentEmail = "profile-student-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "Profile Student", "email", studentEmail,
                            "password", "StudentPassword123", "role", "STUDENT"))))
            .andExpect(status().isOk());
    User createdStudent = users.findByEmail(studentEmail).orElseThrow();
    assertEquals("STUDENT", createdStudent.getRole());
    assertEquals("APPROVED", createdStudent.getStatus());
    assertTrue(studentProfileRepository.existsById(createdStudent.getId()));
    assertFalse(alumniProfileRepository.existsById(createdStudent.getId()));

    String alumniEmail = "profile-alumni-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "Profile Alumni", "email", alumniEmail,
                            "password", "AlumniPassword123", "role", "ALUMNI"))))
            .andExpect(status().isOk());
    User createdAlumni = users.findByEmail(alumniEmail).orElseThrow();
    assertEquals("ALUMNI", createdAlumni.getRole());
    assertEquals("PENDING", createdAlumni.getStatus());
    assertEquals("PENDING", alumniProfileRepository.findById(createdAlumni.getId())
            .orElseThrow().getApprovalStatus());
}

@Test
void signupCreatesUnverifiedUserWithoutOtpAndAllowsImmediateSignin() throws Exception {
    String email = "signup-no-otp-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "New Student",
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "STUDENT",
                            "emailVerified", true,
                            "verified", true))))
            .andExpect(status().isOk());

    User created = users.findByEmail(email).orElseThrow();
    assertFalse(created.isEmailVerified());
    assertEquals("APPROVED", created.getStatus());
    assertTrue(otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            email, "EMAIL_VERIFICATION").isEmpty());
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk());
    org.mockito.Mockito.verifyNoInteractions(emailService);
}

@Test
void signupTrimsAndNormalizesEmailWithoutCreatingOtpOrOutboxEntries() throws Exception {
    String email = "normalized-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "Normalized Student",
                            "email", "  " + email.toUpperCase(java.util.Locale.ROOT) + "  ",
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk());

    User created = users.findByEmail(email).orElseThrow();
    assertEquals(email, created.getEmail());
    assertTrue(otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            email, "EMAIL_VERIFICATION").isEmpty());
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());
}

@Test
void profileUpdateCannotChangeRoleOrStatusAndRepairsMissingRoleProfile() throws Exception {
    assertFalse(studentProfileRepository.existsById(student.getId()));
    mockMvc.perform(put("/users/{id}", student.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"name":"Updated Name","skills":"Dart, Flutter","role":"ADMIN","status":"APPROVED"}
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("STUDENT"))
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.skills").value("Dart, Flutter"));

    User updated = users.findById(student.getId()).orElseThrow();
    assertEquals("STUDENT", updated.getRole());
    assertEquals("APPROVED", updated.getStatus());
    assertEquals("Dart, Flutter", updated.getSkills());
    assertTrue(studentProfileRepository.existsById(student.getId()));
    assertFalse(alumniProfileRepository.existsById(student.getId()));
}

@Test
void userCannotModifyAnotherAccountsProfile() throws Exception {
    mockMvc.perform(put("/users/{id}", admin.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Unauthorized change\"}"))
            .andExpect(status().isForbidden());
    assertEquals("Security Admin", users.findById(admin.getId()).orElseThrow().getName());
}

@Test
void rejectedAccountCannotContinueUsingProtectedEndpoints() throws Exception {
    student.setStatus("REJECTED");
    users.save(student);
    mockMvc.perform(get("/users")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isUnauthorized());
}

@Test
void adminApprovalSynchronizesAuthoritativeUserAndAlumniProfileStatus() throws Exception {
    User alumni = users.save(new User("Pending Alumni", "pending-alumni-" + UUID.randomUUID()
            + "@example.test", passwordEncoder.encode("InitialPassword123"), "ALUMNI", "PENDING"));

    mockMvc.perform(put("/alumni/approve/{id}", alumni.getId())
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"));

    assertEquals("APPROVED", users.findById(alumni.getId()).orElseThrow().getStatus());
    assertEquals("APPROVED", alumniProfileRepository.findById(alumni.getId())
            .orElseThrow().getApprovalStatus());
}

@Test
void adminCreatedAlumniGetsPendingProfileWithoutEmailVerificationFlow() throws Exception {
    String email = "admin-created-alumni-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/alumni")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "Created Alumni", "email", email,
                            "password", "InitialPassword123"))))
            .andExpect(status().isOk());
    User created = users.findByEmail(email).orElseThrow();
    assertFalse(created.isEmailVerified());
    assertEquals("ALUMNI", created.getRole());
    assertEquals("PENDING", created.getStatus());
    assertEquals("PENDING", alumniProfileRepository.findById(created.getId())
            .orElseThrow().getApprovalStatus());
    assertTrue(otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            email, "EMAIL_VERIFICATION").isEmpty());
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void signupDoesNotDependOnEmailProviderAndDuplicateDoesNotCreateAnotherUser() throws Exception {
    String email = "no-email-provider-" + UUID.randomUUID() + "@example.test";
    String payload = new ObjectMapper().writeValueAsString(java.util.Map.of(
            "name", "New Student",
            "email", email,
            "password", "OriginalPassword123",
            "role", "STUDENT"));

    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isOk())
            .andExpect(content().string("Signup successful"));
    User created = users.findByEmail(email).orElseThrow();
    String originalHash = created.getPassword();
    assertFalse(created.isEmailVerified());
    assertTrue(studentProfileRepository.existsById(created.getId()));
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());

    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isConflict());
    assertEquals(originalHash, users.findByEmail(email).orElseThrow().getPassword());
    assertFalse(users.findByEmail(email).orElseThrow().isEmailVerified());
    assertTrue(studentProfileRepository.existsById(created.getId()));
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());
    verify(emailService, org.mockito.Mockito.never()).sendOtpEmail(anyString(), anyString());

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "OriginalPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk());
}

@Test
void duplicateSignupCannotReplacePasswordOrRole() throws Exception {
    String email = "existing-signup-" + UUID.randomUUID() + "@example.test";
    String originalPassword = passwordEncoder.encode("OriginalPassword123");
    User existing = users.save(new User(
            "Existing Student", email, originalPassword, "STUDENT", "APPROVED"));

    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "Attacker",
                            "email", email,
                            "password", "AttackerPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isConflict());

    User unchanged = users.findById(existing.getId()).orElseThrow();
    assertEquals("Existing Student", unchanged.getName());
    assertEquals("STUDENT", unchanged.getRole());
    assertEquals(originalPassword, unchanged.getPassword());
    assertFalse(unchanged.isEmailVerified());
    verify(emailService, org.mockito.Mockito.never()).sendOtpEmail(anyString(), anyString());
}

@Test
void signinAllowsUnverifiedAccountButEnforcesRoleAndApprovalStatus() throws Exception {
    String email = "role-login-" + UUID.randomUUID() + "@example.test";
    User verified = new User(
            "Role Student",
            email,
            passwordEncoder.encode("StudentPassword123"),
            "STUDENT",
            "APPROVED");
    users.save(verified);

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.not(
                    org.hamcrest.Matchers.emptyString())));

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error")
                    .value("Invalid email, password, or account type"));

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "WrongPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("Invalid credentials"));

    User unverified = new User(
            "Pending Student",
            "pending-verification-" + UUID.randomUUID() + "@example.test",
            passwordEncoder.encode("StudentPassword123"),
            "STUDENT",
            "APPROVED");
    users.save(unverified);
    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", unverified.getEmail(),
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk());

    User pending = new User(
            "Pending Alumni",
            "pending-approval-" + UUID.randomUUID() + "@example.test",
            passwordEncoder.encode("StudentPassword123"),
            "ALUMNI",
            "PENDING");
    pending.setEmailVerified(true);
    users.save(pending);
    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", pending.getEmail(),
                            "password", "StudentPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Account pending approval"));

    User rejected = new User(
            "Rejected Alumni",
            "rejected-" + UUID.randomUUID() + "@example.test",
            passwordEncoder.encode("StudentPassword123"),
            "ALUMNI",
            "REJECTED");
    rejected.setEmailVerified(true);
    users.save(rejected);
    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", rejected.getEmail(),
                            "password", "StudentPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Account rejected"));
}

@Test
void legacyEmailVerificationOutboxEntriesAreDiscardedWithoutSending() {
    String email = "legacy-verification-" + UUID.randomUUID() + "@example.test";
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    emailOutboxRepository.save(new EmailOutboxMessage(
            email,
            emailOutboxCipher.encrypt("123456"),
            "EMAIL_VERIFICATION",
            now,
            now.plusMinutes(3)));

    new EmailOutboxDispatcher(emailOutboxService, emailOutboxCipher, emailService)
            .dispatchNext();

    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());
    org.mockito.Mockito.verifyNoInteractions(emailService);
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void concurrentSignupCreatesOnlyOneAccountAndRejectsDuplicate() throws Exception {
    String email = "concurrent-signup-" + UUID.randomUUID() + "@example.test";
    String payload = new ObjectMapper().writeValueAsString(java.util.Map.of(
            "name", "Concurrent Student",
            "email", email,
            "password", "ConcurrentPassword123",
            "role", "STUDENT"));
    java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
        java.util.List<java.util.concurrent.Future<Integer>> responses =
                executor.invokeAll(java.util.List.of(
                        () -> mockMvc.perform(post("/signup")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                                .andReturn().getResponse().getStatus(),
                        () -> mockMvc.perform(post("/signup")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(payload))
                                .andReturn().getResponse().getStatus()));
        java.util.List<Integer> statuses = java.util.List.of(
                responses.get(0).get(),
                responses.get(1).get());
        assertEquals(1, statuses.stream().filter(status -> status == 200).count());
        assertEquals(1, statuses.stream().filter(status -> status == 409).count());
        assertEquals(1, users.findAll().stream()
                .filter(user -> email.equals(user.getEmail()))
                .count());
        assertTrue(studentProfileRepository.existsById(
                users.findByEmail(email).orElseThrow().getId()));
    } finally {
        executor.shutdownNow();
    }
}

@Test
void nonAdminCannotAccessAdminQueuesOrApproveEvents() throws Exception {
    mockMvc.perform(get("/alumni/pending")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isForbidden());
    mockMvc.perform(put("/events/approve/{id}", 987654L)
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isForbidden());
}

@Test
void alumniAdminEndpointsRejectWrongRoleTargetsAndProtectAdminSelf() throws Exception {
    mockMvc.perform(put("/alumni/approve/{id}", student.getId())
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isBadRequest());
    assertEquals("APPROVED", users.findById(student.getId()).orElseThrow().getStatus());

    mockMvc.perform(delete("/alumni/{id}", admin.getId())
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());
    assertTrue(users.existsById(admin.getId()));
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void deletingAlumniDoesNotCascadeDeleteReferencedEvents() throws Exception {
    Long alumniId = new TransactionTemplate(transactionManager).execute(status -> {
        User alumni = users.save(new User("Referenced Alumni", "referenced-alumni-"
                + UUID.randomUUID() + "@example.test", "encoded", "ALUMNI", "APPROVED"));
        alumni.setEmailVerified(true);
        alumniProfileRepository.save(new com.alumni.alumni_connect.entity.AlumniProfile(alumni));
        com.alumni.alumni_connect.entity.Event event = new com.alumni.alumni_connect.entity.Event();
        event.setTitle("Owned event");
        event.setCreator(alumni);
        event.setRole("ALUMNI");
        event.setStatus("APPROVED");
        eventRepository.saveAndFlush(event);
        return alumni.getId();
    });

    mockMvc.perform(delete("/alumni/{id}", alumniId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());

    assertTrue(users.existsById(alumniId));
    assertTrue(alumniProfileRepository.existsById(alumniId));
    assertEquals(1, eventRepository.count());
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void adminCanDeleteOnlyUnreferencedAlumniAndRemovesItsProfile() throws Exception {
    Long alumniId = new TransactionTemplate(transactionManager).execute(status -> {
        User alumni = users.save(new User("Disposable Alumni", "disposable-alumni-"
                + UUID.randomUUID() + "@example.test", "encoded", "ALUMNI", "REJECTED"));
        alumniProfileRepository.save(new com.alumni.alumni_connect.entity.AlumniProfile(alumni));
        return alumni.getId();
    });

    mockMvc.perform(delete("/alumni/{id}", alumniId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());
    assertFalse(users.existsById(alumniId));
    assertFalse(alumniProfileRepository.existsById(alumniId));
}

@Test
void wrongOtpCannotAuthorizeButValidOtpReturnsShortLivedOpaqueToken() throws Exception {
    String token = requestResetAuthorization(student.getEmail(), true);
    assertEquals(43, token.length());
    Otp otp = otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            student.getEmail(), "PASSWORD_RESET").orElseThrow();
    assertTrue(otp.isVerified());
    assertNotNull(otp.getConsumedAt());
    assertNotNull(otp.getResetTokenExpiry());
    assertNotEquals(token, otp.getResetTokenHash());
    assertEquals(64, otp.getResetTokenHash().length());
}

@Test
void resetRejectsInvalidAndExpiredAuthorizationWithoutChangingPassword() throws Exception {
    String token = requestResetAuthorization(student.getEmail(), false);
    String originalHash = student.getPassword();

    mockMvc.perform(post("/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            resetPayload(student.getEmail(), "wrong-token", "NewPassword123"))))
            .andExpect(status().isBadRequest());
    assertEquals(originalHash, users.findByEmail(student.getEmail()).orElseThrow().getPassword());

    Otp otp = otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            student.getEmail(), "PASSWORD_RESET").orElseThrow();
    otp.setResetTokenExpiry(java.time.LocalDateTime.now().minusSeconds(1));
    otpRepository.save(otp);
    mockMvc.perform(post("/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            resetPayload(student.getEmail(), token, "NewPassword123"))))
            .andExpect(status().isBadRequest());
    assertEquals(originalHash, users.findByEmail(student.getEmail()).orElseThrow().getPassword());
}

@Test
void resetAuthorizationIsAccountBoundSingleUseAndChangesPasswordOnlyOnce() throws Exception {
    String token = requestResetAuthorization(student.getEmail(), false);
    String originalStudentHash = student.getPassword();
    String originalAdminHash = admin.getPassword();

    mockMvc.perform(post("/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            resetPayload(admin.getEmail(), token, "AttackerPassword123"))))
            .andExpect(status().isBadRequest());
    assertEquals(originalAdminHash, users.findByEmail(admin.getEmail()).orElseThrow().getPassword());
    assertEquals(originalStudentHash, users.findByEmail(student.getEmail()).orElseThrow().getPassword());

    mockMvc.perform(post("/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            resetPayload(student.getEmail(), token, "NewPassword123"))))
            .andExpect(status().isOk())
            .andExpect(content().string("Password reset successful"));
    String changedHash = users.findByEmail(student.getEmail()).orElseThrow().getPassword();
    assertNotEquals(originalStudentHash, changedHash);
    assertTrue(passwordEncoder.matches("NewPassword123", changedHash));

    mockMvc.perform(post("/reset-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            resetPayload(student.getEmail(), token, "AnotherPassword123"))))
            .andExpect(status().isBadRequest());
    assertEquals(changedHash, users.findByEmail(student.getEmail()).orElseThrow().getPassword());
}

private String requestResetAuthorization(String email, boolean tryWrongOtpFirst) throws Exception {
    ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
    mockMvc.perform(post("/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of("email", email))))
            .andExpect(status().isOk())
            .andExpect(content().string("If an account exists, a code will be sent shortly"));

    new EmailOutboxDispatcher(emailOutboxService, emailOutboxCipher, emailService)
            .dispatchNext();
    verify(emailService).sendOtpEmail(org.mockito.ArgumentMatchers.eq(email), otpCaptor.capture());
    String otp = otpCaptor.getValue();
    assertTrue(otp.matches("\\d{6}"));

    if (tryWrongOtpFirst) {
        String wrongOtp = (otp.charAt(0) == '0' ? "1" : "0") + otp.substring(1);
        mockMvc.perform(post("/verify-otp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(
                                java.util.Map.of("email", email, "otp", wrongOtp))))
                .andExpect(status().isBadRequest());
    }

    String response = mockMvc.perform(post("/verify-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            java.util.Map.of("email", email, "otp", otp))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    return new ObjectMapper().readTree(response).get("resetToken").asText();
}

private java.util.Map<String, String> resetPayload(String email, String token, String password) {
    return java.util.Map.of("email", email, "resetToken", token, "newPassword", password);
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
@org.springframework.transaction.annotation.Transactional(
        propagation = Propagation.NOT_SUPPORTED)
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
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(connectionId))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.requester.email").value(student.getEmail()))
            .andExpect(jsonPath("$.receiver.email").value(admin.getEmail()));

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
void connectionStatusTransitionsAreCaseInsensitiveAndProtectedAfterProcessing() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"status\":\"accepted\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "rejected"))
            .andExpect(status().isConflict());
}

@Test
void unauthenticatedUserCannotAcceptConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long connectionId = new ObjectMapper().readTree(response).get("id").asLong();

    mockMvc.perform(put("/connections/{id}", connectionId).param("status", "ACCEPTED"))
            .andExpect(status().isUnauthorized());
}

@Test
void acceptingMissingConnectionReturnsNotFound() throws Exception {
    mockMvc.perform(put("/connections/{id}", Long.MAX_VALUE)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isNotFound());
}

@Test
void rejectedConnectionCanBeRetriedAndAcceptedWithoutCreatingAnotherRow() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long connectionId = new ObjectMapper().readTree(response).get("id").asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "REJECTED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isConflict());

    String retried = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.junit.jupiter.api.Assertions.assertEquals(
            connectionId, new ObjectMapper().readTree(retried).get("id").asLong());

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACCEPTED"));

    mockMvc.perform(get("/connections")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(connectionId))
            .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
}
@Test
void adminCanApproveEvent() throws Exception {
    String response = mockMvc.perform(
            post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
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
void alumniCanCreateEventAndStudentCannot() throws Exception {
    mockMvc.perform(post("/events")
                    .header("Authorization",
                            bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "title": "Test Event",
                              "description": "Security API test event",
                              "location": "College Campus"
                            }
                            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.createdBy").value(alumni.getEmail()));

    mockMvc.perform(post("/events")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"Student event\"}"))
            .andExpect(status().isForbidden());
}
@Test
void adminCanAccessAllEvents() throws Exception {
    mockMvc.perform(get("/events/all")
                    .header("Authorization",
                            bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());
}

}
