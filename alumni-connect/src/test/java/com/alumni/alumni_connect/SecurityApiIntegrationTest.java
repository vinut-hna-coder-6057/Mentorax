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
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.TestPropertySource;
import java.time.LocalDateTime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.alumni.alumni_connect.entity.Event;
import com.alumni.alumni_connect.entity.EventRegistration;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.EventRegistrationRepository;
import com.alumni.alumni_connect.repository.EventRepository;
import com.alumni.alumni_connect.repository.UserRepository;
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.email-outbox.enabled=false")

class SecurityApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;
    @Autowired
private NotificationRepository notificationRepository;
@Autowired
private MessageRepository messageRepository;
@Autowired
private EventRepository eventRepository;

@Autowired
private EventRegistrationRepository eventRegistrationRepository;

@Autowired
private UserRepository userRepository;
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
        alumni.setJobRole("Data Engineer");
        alumni.setSkills("data engineering");
        admin = users.save(new User("Security Admin", "security-admin-" + suffix + "@example.test",
                "not-used", "ADMIN", "APPROVED"));
        student.setEmailVerified(true);
        alumni.setEmailVerified(true);
        users.save(student);
        users.save(alumni);
    }

    @Test
    void protectedEndpointWithoutAuthenticationReturnsJson401() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"Authentication required\"}"));
    }

    @Test
    void mentorDiscoveryRequiresStudentAccessAndValidatesGoals() throws Exception {
        User pendingAlumni = users.save(new User("Pending Mentor", "pending-mentor-" + UUID.randomUUID()
                + "@example.test", "not-used", "ALUMNI", "PENDING"));

        mockMvc.perform(post("/ai/mentor-discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"data engineering\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", "Bearer invalid.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"data engineering\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"data engineering\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"data engineering\"}"))
                .andExpect(status().isForbidden());

        String studentToken = bearer(student.getEmail(), "STUDENT");
        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/ai/mentor-discovery")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"How can I prepare for data engineering roles?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackUsed").value(true))
                .andExpect(jsonPath("$.intent").value("CAREER_MENTOR_SEARCH"))
                .andExpect(jsonPath("$.recommendations[*].id").value(
                        org.hamcrest.Matchers.hasItem(alumni.getId().intValue())))
                .andExpect(jsonPath("$.recommendations[*].id").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(pendingAlumni.getId().intValue()))));

        mockMvc.perform(post("/ai/career-assistant")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"Who is the Prime Minister of India?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent").value("GENERAL_KNOWLEDGE"))
                .andExpect(jsonPath("$.recommendations").isEmpty())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not configured")));

        mockMvc.perform(post("/ai/career-assistant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"data engineering\"}"))
                .andExpect(status().isUnauthorized());
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
void registeredUserReturnsRegisteredTrue() throws Exception {
    User user = new User();
    user.setName("Registered Student");
    user.setEmail("registered.student@test.com");
    user.setPassword("password");
    user.setRole("STUDENT");
    user.setStatus("APPROVED");
    user.setEmailVerified(true);

    user = userRepository.save(user);

    Event event = new Event();
    event.setTitle("Test Event");
    event.setDescription("Registration status test");
    event.setLocation("Test Location");
   event.setEventDate(LocalDateTime.now().plusDays(1).toString());
    event.setAttendeeCount(1);
    event.setStatus("APPROVED");
   event.setCreatedBy(user.getEmail());

    event = eventRepository.save(event);

    EventRegistration registration = new EventRegistration();
    registration.setEvent(event);
    registration.setUser(user);
    registration.setRegisteredAt(LocalDateTime.now());

    eventRegistrationRepository.save(registration);

    mockMvc.perform(
            get("/events/" + event.getId() + "/registration-status")
                    .with(user(user.getEmail()))
    )
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.registered", is(true)));
}
@Test
void unregisteredUserReturnsRegisteredFalse() throws Exception {
    User user = new User();
    user.setName("Unregistered Student");
    user.setEmail("unregistered.student@test.com");
    user.setPassword("password");
    user.setRole("STUDENT");
    user.setStatus("APPROVED");
    user.setEmailVerified(true);

    user = userRepository.save(user);

    Event event = new Event();
    event.setTitle("Test Event 2");
    event.setDescription("Registration status test");
    event.setLocation("Test Location");
    event.setEventDate(LocalDateTime.now().plusDays(1).toString());
    event.setAttendeeCount(0);
    event.setStatus("APPROVED");
    event.setCreatedBy(user.getEmail());

    event = eventRepository.save(event);

    mockMvc.perform(
            get("/events/" + event.getId() + "/registration-status")
                    .with(user(user.getEmail()))
    )
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.registered", is(false)));
}
@Test
void nonExistentEventReturns404() throws Exception {
    User user = new User();
    user.setName("Test Student");
    user.setEmail("nonexistent.event@test.com");
    user.setPassword("password");
    user.setRole("STUDENT");
    user.setStatus("APPROVED");
    user.setEmailVerified(true);

    user = userRepository.save(user);

    mockMvc.perform(
            get("/events/999999999/registration-status")
                    .with(user(user.getEmail()))
    )
    .andExpect(status().isNotFound());
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
    student.setRollno("private-event-roll");
    student.setSection("private-event-section");
    student.setLocation("private event address");
    users.save(student);

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
                    .value(student.getEmail()))
            .andExpect(jsonPath("$[0].user.id").value(student.getId()))
            .andExpect(jsonPath("$[0].user.name").value(student.getName()))
            .andExpect(jsonPath("$[0].user.email").doesNotExist())
            .andExpect(jsonPath("$[0].user.rollno").doesNotExist())
            .andExpect(jsonPath("$[0].user.section").doesNotExist())
            .andExpect(jsonPath("$[0].user.location").doesNotExist());
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
void eventMeetingLinkIsVisibleOnlyToCreatorAdminAndRegistrants() throws Exception {
    String eventResponse = mockMvc.perform(post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"title":"Private meeting","meetingLink":"https://meet.example.test/secret"}
                            """))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    long eventId = new ObjectMapper().readTree(eventResponse).get("id").asLong();

    mockMvc.perform(get("/events"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")].meetingLink")
                    .value(org.hamcrest.Matchers.contains((Object) null)));

    mockMvc.perform(get("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")].meetingLink")
                    .value(org.hamcrest.Matchers.contains((Object) null)));

    mockMvc.perform(get("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")].meetingLink")
                    .value(org.hamcrest.Matchers.contains("https://meet.example.test/secret")));

    mockMvc.perform(post("/events/register")
                    .param("eventId", String.valueOf(eventId))
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk());

    mockMvc.perform(get("/events")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == " + eventId + ")].meetingLink")
                    .value(org.hamcrest.Matchers.contains("https://meet.example.test/secret")));
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void concurrentEventRegistrationsRespectCapacityAndCancellationReleasesSlot() throws Exception {
    Event event = new Event();
    event.setTitle("Capacity-limited event");
    event.setCreator(admin);
    event.setStatus("APPROVED");
    event.setMaxCapacity(1);
    event.setAttendeeCount(0);
    event.setCreatedAt(LocalDateTime.now());
    event = eventRepository.saveAndFlush(event);
    long eventId = event.getId();

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
        Callable<RegistrationAttempt> studentAttempt = registrationAttempt(
                student.getEmail(), "STUDENT", eventId, ready, start);
        Callable<RegistrationAttempt> alumniAttempt = registrationAttempt(
                alumni.getEmail(), "ALUMNI", eventId, ready, start);
        Future<RegistrationAttempt> studentResult = executor.submit(studentAttempt);
        Future<RegistrationAttempt> alumniResult = executor.submit(alumniAttempt);

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        RegistrationAttempt first = studentResult.get(10, TimeUnit.SECONDS);
        RegistrationAttempt second = alumniResult.get(10, TimeUnit.SECONDS);
        assertEquals(Set.of(200, 409), Set.of(first.status(), second.status()));
        assertEquals(1, eventRegistrationRepository.countByEventId(eventId));
        assertEquals(1, eventRepository.findById(eventId).orElseThrow().getAttendeeCount());

        RegistrationAttempt winner = first.status() == 200 ? first : second;
        RegistrationAttempt waiting = first.status() == 409 ? first : second;
        mockMvc.perform(delete("/events/register")
                        .param("eventId", String.valueOf(eventId))
                        .header("Authorization", bearer(winner.email(), winner.role())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/events/register")
                        .param("eventId", String.valueOf(eventId))
                        .header("Authorization", bearer(waiting.email(), waiting.role())))
                .andExpect(status().isOk());
        assertEquals(1, eventRegistrationRepository.countByEventId(eventId));
        assertEquals(1, eventRepository.findById(eventId).orElseThrow().getAttendeeCount());
    } finally {
        start.countDown();
        executor.shutdownNow();
    }
}

@Test
void eventCreationRejectsBlankRequiredTitle() throws Exception {
    mockMvc.perform(post("/events")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"  \"}"))
            .andExpect(status().isBadRequest());

    mockMvc.perform(post("/events")
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"title":"Invalid capacity","maxCapacity":0}
                            """))
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

    private Callable<RegistrationAttempt> registrationAttempt(
            String email,
            String role,
            long eventId,
            CountDownLatch ready,
            CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Registration test start signal timed out");
            }
            int responseStatus = mockMvc.perform(post("/events/register")
                            .param("eventId", String.valueOf(eventId))
                            .header("Authorization", bearer(email, role)))
                    .andReturn()
                    .getResponse()
                    .getStatus();
            return new RegistrationAttempt(email, role, responseStatus);
        };
    }

    private record RegistrationAttempt(String email, String role, int status) {}

    private String bearer(String email, String role) {
        return "Bearer " + jwtUtil.generateToken(email, role);
    }

    private String dispatchVerificationOtp(String email) {
        String[] deliveredCode = new String[1];
        doAnswer(invocation -> {
            deliveredCode[0] = invocation.getArgument(1);
            return null;
        }).when(emailService).sendEmailVerificationOtp(eq(email), anyString());
        new EmailOutboxDispatcher(emailOutboxService, emailOutboxCipher, emailService)
                .dispatchNext();
        assertNotNull(deliveredCode[0]);
        assertTrue(deliveredCode[0].matches("\\d{6}"));
        return deliveredCode[0];
    }

    private String verificationPayload(String email, String code) {
        return """
                {"email":"%s","otp":"%s"}
                """.formatted(email, code);
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
void directoryAndOtherApprovedProfilesDoNotExposePrivateIdentifiers() throws Exception {
    student.setRollno("private-roll-" + UUID.randomUUID());
    student.setSection("private-section");
    student.setLocation("private street address");
    student.setSkills("Java, Spring");
    users.save(student);
    alumni.setRollno("private-alumni-roll-" + UUID.randomUUID());
    alumni.setSection("private-alumni-section");
    alumni.setLocation("private alumni address");
    users.save(alumni);

    String directoryJson = mockMvc.perform(get("/users/students")
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var directoryProfiles = new ObjectMapper().readTree(directoryJson);
    var directoryStudent = java.util.stream.StreamSupport.stream(
                    directoryProfiles.spliterator(), false)
            .filter(profile -> profile.path("id").asLong() == student.getId())
            .findFirst()
            .orElseThrow();
    assertTrue(directoryStudent.has("skills"));
    assertFalse(directoryStudent.has("email"));
    assertFalse(directoryStudent.has("rollno"));
    assertFalse(directoryStudent.has("section"));
    assertFalse(directoryStudent.has("location"));

    for (String alumniEndpoint : java.util.List.of("/alumni", "/alumni/approved")) {
        String alumniJson = mockMvc.perform(get(alumniEndpoint)
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var alumniProfiles = new ObjectMapper().readTree(alumniJson);
        var directoryAlumni = java.util.stream.StreamSupport.stream(
                        alumniProfiles.spliterator(), false)
                .filter(profile -> profile.path("id").asLong() == alumni.getId())
                .findFirst()
                .orElseThrow();
        assertFalse(directoryAlumni.has("email"));
        assertFalse(directoryAlumni.has("rollno"));
        assertFalse(directoryAlumni.has("section"));
        assertFalse(directoryAlumni.has("location"));
    }

    mockMvc.perform(get("/users/{id}", student.getId())
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").doesNotExist())
            .andExpect(jsonPath("$.rollno").doesNotExist())
            .andExpect(jsonPath("$.section").doesNotExist())
            .andExpect(jsonPath("$.location").doesNotExist())
            .andExpect(jsonPath("$.skills").value("Java, Spring"));

    mockMvc.perform(get("/users/{id}", student.getId())
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(student.getEmail()))
            .andExpect(jsonPath("$.rollno").value(student.getRollno()))
            .andExpect(jsonPath("$.section").value("private-section"))
            .andExpect(jsonPath("$.location").value("private street address"));

    mockMvc.perform(get("/users/email/{email}", student.getEmail())
                    .header("Authorization", bearer(alumni.getEmail(), "ALUMNI")))
            .andExpect(status().isForbidden());
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
            .andExpect(status().isForbidden());

    mockMvc.perform(get("/users/{id}", alumni.getId())
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"));
}

@Test
void emailVerificationRevocationInvalidatesExistingHttpTokens() throws Exception {
    student.setEmailVerified(false);
    users.save(student);

    mockMvc.perform(get("/users")
                    .header("Authorization", bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isUnauthorized());
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
    assertEquals("PENDING", createdStudent.getStatus());
    assertFalse(createdStudent.isEmailVerified());
    assertTrue(studentProfileRepository.existsById(createdStudent.getId()));
    assertFalse(alumniProfileRepository.existsById(createdStudent.getId()));
    assertTrue(otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            studentEmail, "EMAIL_VERIFICATION").isPresent());
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(studentEmail).isPresent());

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
    assertFalse(createdAlumni.isEmailVerified());
    assertEquals("PENDING", alumniProfileRepository.findById(createdAlumni.getId())
            .orElseThrow().getApprovalStatus());
    assertTrue(otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            alumniEmail, "EMAIL_VERIFICATION").isPresent());
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(alumniEmail).isPresent());
}

@Test
void emailVerificationActivatesStudentsButDoesNotApproveAlumni() throws Exception {
    String email = "signup-verification-" + UUID.randomUUID() + "@example.test";
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
    assertEquals("PENDING", created.getStatus());

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Email verification required"));

    dispatchVerificationOtp(email);
    mockMvc.perform(post("/resend-verification")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            java.util.Map.of("email", email))))
            .andExpect(status().isOk())
            .andExpect(content().string(
                    "If an account requires email verification, a code will be sent shortly"));
    String currentCode = dispatchVerificationOtp(email);
    Otp activeVerification = otpRepository
            .findFirstByEmailAndPurposeOrderByIdDesc(email, "EMAIL_VERIFICATION")
            .orElseThrow();
    assertNotEquals(currentCode, activeVerification.getCodeHash());
    assertTrue(passwordEncoder.matches(currentCode, activeVerification.getCodeHash()));

    mockMvc.perform(post("/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(verificationPayload(email, currentCode)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailVerified").value(true))
            .andExpect(jsonPath("$.approvalPending").value(false));

    User verifiedStudent = users.findByEmail(email).orElseThrow();
    assertTrue(verifiedStudent.isEmailVerified());
    assertEquals("APPROVED", verifiedStudent.getStatus());
    assertTrue(otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
            email, "EMAIL_VERIFICATION").isEmpty());

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk());

    mockMvc.perform(post("/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(verificationPayload(email, currentCode)))
            .andExpect(status().isBadRequest());

    String alumniEmail = "signup-alumni-verification-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "New Alumni",
                            "email", alumniEmail,
                            "password", "AlumniPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isOk());
    String alumniCode = dispatchVerificationOtp(alumniEmail);

    mockMvc.perform(post("/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(verificationPayload(alumniEmail, alumniCode)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.emailVerified").value(true))
            .andExpect(jsonPath("$.approvalPending").value(true));

    User verifiedAlumni = users.findByEmail(alumniEmail).orElseThrow();
    assertTrue(verifiedAlumni.isEmailVerified());
    assertEquals("PENDING", verifiedAlumni.getStatus());
    assertEquals("PENDING", alumniProfileRepository.findById(verifiedAlumni.getId())
            .orElseThrow().getApprovalStatus());

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", alumniEmail,
                            "password", "AlumniPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Account pending approval"));

    mockMvc.perform(put("/approve/{id}", verifiedAlumni.getId())
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isOk());
    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", alumniEmail,
                            "password", "AlumniPassword123",
                            "role", "ALUMNI"))))
            .andExpect(status().isOk());
}

@Test
@Transactional(propagation = Propagation.NOT_SUPPORTED)
void failedEmailVerificationAttemptIsPersisted() throws Exception {
    String email = "failed-verification-" + UUID.randomUUID() + "@example.test";
    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "name", "Verification Student",
                            "email", email,
                            "password", "StudentPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isOk());

    String validCode = dispatchVerificationOtp(email);
    String invalidCode = "000000".equals(validCode) ? "000001" : "000000";
    mockMvc.perform(post("/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(verificationPayload(email, invalidCode)))
            .andExpect(status().isBadRequest());

    Integer attemptCount = new TransactionTemplate(transactionManager).execute(status ->
            otpRepository.findFirstByEmailAndPurposeOrderByIdDesc(
                            email, "EMAIL_VERIFICATION")
                    .orElseThrow()
                    .getAttemptCount());
    assertEquals(1, attemptCount);
}

@Test
void signupTrimsAndNormalizesEmailBeforeCreatingVerificationOtp() throws Exception {
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
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isPresent());
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
            .andExpect(content().string(
                    "If this address is eligible, verification instructions will be sent shortly."));
    User created = users.findByEmail(email).orElseThrow();
    String originalHash = created.getPassword();
    assertFalse(created.isEmailVerified());
    assertTrue(studentProfileRepository.existsById(created.getId()));
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isPresent());

    mockMvc.perform(post("/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(status().isOk())
            .andExpect(content().string(
                    "If this address is eligible, verification instructions will be sent shortly."));
    assertEquals(originalHash, users.findByEmail(email).orElseThrow().getPassword());
    assertFalse(users.findByEmail(email).orElseThrow().isEmailVerified());
    assertTrue(studentProfileRepository.existsById(created.getId()));
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isPresent());
    verify(emailService, org.mockito.Mockito.never()).sendOtpEmail(anyString(), anyString());
    verify(emailService, org.mockito.Mockito.never())
            .sendEmailVerificationOtp(anyString(), anyString());

    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", email,
                            "password", "OriginalPassword123",
                            "role", "STUDENT"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Email verification required"));
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
            .andExpect(status().isOk())
            .andExpect(content().string(
                    "If this address is eligible, verification instructions will be sent shortly."));

    User unchanged = users.findById(existing.getId()).orElseThrow();
    assertEquals("Existing Student", unchanged.getName());
    assertEquals("STUDENT", unchanged.getRole());
    assertEquals(originalPassword, unchanged.getPassword());
    assertFalse(unchanged.isEmailVerified());
    verify(emailService, org.mockito.Mockito.never()).sendOtpEmail(anyString(), anyString());
}

@Test
void signinRequiresVerificationAndStillEnforcesRoleAndApprovalStatus() throws Exception {
    String email = "role-login-" + UUID.randomUUID() + "@example.test";
    User verified = new User(
            "Role Student",
            email,
            passwordEncoder.encode("StudentPassword123"),
            "STUDENT",
            "APPROVED");
    verified.setEmailVerified(true);
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
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("Email verification required"));

    User adminUser = new User(
            "Unchanged Admin",
            "admin-login-" + UUID.randomUUID() + "@example.test",
            passwordEncoder.encode("AdminPassword123"),
            "ADMIN",
            "APPROVED");
    users.save(adminUser);
    mockMvc.perform(post("/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(java.util.Map.of(
                            "email", adminUser.getEmail(),
                            "password", "AdminPassword123",
                            "role", "ADMIN"))))
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
void emailVerificationOutboxEntriesUseTheEncryptedDeliveryPipeline() {
    String email = "legacy-verification-" + UUID.randomUUID() + "@example.test";
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    String encryptedCode = emailOutboxCipher.encrypt("123456");
    emailOutboxRepository.save(new EmailOutboxMessage(
            email,
            encryptedCode,
            "EMAIL_VERIFICATION",
            now,
            now.plusMinutes(3)));
    assertNotEquals("123456", encryptedCode);
    assertEquals("123456", emailOutboxCipher.decrypt(encryptedCode));

    new EmailOutboxDispatcher(emailOutboxService, emailOutboxCipher, emailService)
            .dispatchNext();

    verify(emailService).sendEmailVerificationOtp(eq(email), eq("123456"));
    assertTrue(emailOutboxRepository.findFirstByRecipientOrderByIdDesc(email).isEmpty());
    verify(emailService, org.mockito.Mockito.never()).sendOtpEmail(anyString(), anyString());
}

@Test
void verificationAndResendResponsesDoNotRevealWhetherAnEmailExists() throws Exception {
    String unknownEmail = "unknown-verification-" + UUID.randomUUID() + "@example.test";
    String resendBody = "If an account requires email verification, a code will be sent shortly";

    String knownResend = mockMvc.perform(post("/resend-verification")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            java.util.Map.of("email", student.getEmail()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    String unknownResend = mockMvc.perform(post("/resend-verification")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(new ObjectMapper().writeValueAsString(
                            java.util.Map.of("email", unknownEmail))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertEquals(resendBody, knownResend);
    assertEquals(knownResend, unknownResend);

    String knownVerify = mockMvc.perform(post("/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(verificationPayload(student.getEmail(), "000000")))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
    String unknownVerify = mockMvc.perform(post("/verify-email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(verificationPayload(unknownEmail, "000000")))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
    assertEquals(knownVerify, unknownVerify);
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
        assertEquals(2, statuses.stream().filter(status -> status == 200).count());
        assertEquals(0, statuses.stream().filter(status -> status == 409).count());
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
    long[] ids = new TransactionTemplate(transactionManager).execute(status -> {
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
        return new long[]{alumni.getId(), event.getId()};
    });

    mockMvc.perform(delete("/alumni/{id}", ids[0])
                    .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
            .andExpect(status().isConflict());

    assertTrue(users.existsById(ids[0]));
    assertTrue(alumniProfileRepository.existsById(ids[0]));
    assertTrue(eventRepository.existsById(ids[1]));
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
