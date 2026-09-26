package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.Connection;
import com.alumni.alumni_connect.entity.Event;
import com.alumni.alumni_connect.entity.Notification;
import com.alumni.alumni_connect.entity.Otp;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.ConnectionRepository;
import com.alumni.alumni_connect.repository.EventRegistrationRepository;
import com.alumni.alumni_connect.repository.EventRepository;
import com.alumni.alumni_connect.repository.NotificationRepository;
import com.alumni.alumni_connect.repository.OtpRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.alumni.alumni_connect.security.JwtUtil;
import com.alumni.alumni_connect.service.AuthService;
import com.alumni.alumni_connect.service.ConnectionService;
import com.alumni.alumni_connect.service.EmailService;
import com.alumni.alumni_connect.service.EventService;
import com.alumni.alumni_connect.service.NotificationService;
import com.alumni.alumni_connect.service.OtpService;
import com.alumni.alumni_connect.service.PasswordResetService;
import com.alumni.alumni_connect.dto.ForgotPasswordRequest;
import com.alumni.alumni_connect.dto.UserProfileResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductionHardeningTest {

    @Mock UserRepository users;
    @Mock StudentProfileRepository students;
    @Mock AlumniProfileRepository alumniProfiles;
    @Mock JwtUtil jwtUtil;
    @Mock EventRepository events;
    @Mock EventRegistrationRepository registrations;
    @Mock EmailService email;
    @Mock CurrentUserService currentUser;
    @Mock ConnectionRepository connections;
    @Mock NotificationRepository notifications;
    @Mock SimpMessagingTemplate messaging;
    @Mock OtpRepository otps;
    @Mock OtpService otpService;
    @Mock BCryptPasswordEncoder encoder;

    @Test
    void publicSignupCannotCreateAdmin() {
        User request = user("new@example.com", "ADMIN", 9L);
        request.setPassword("long-enough-password");

        AuthService service = new AuthService(
                users,
                encoder,
                jwtUtil,
                students,
                alumniProfiles,
                otpService,
                email
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> service.signup(request)
        );

        verify(users, never()).save(any());
    }

    @Test
    void profileResponseDoesNotSerializePassword() throws Exception {
        User user = user("profile@example.com", "STUDENT", 12L);
        user.setPassword("hashed-password");

        String json = new ObjectMapper()
                .writeValueAsString(UserProfileResponse.from(user));

        assertFalse(json.contains("password"));
        assertFalse(json.contains("hashed-password"));
        assertTrue(json.contains("profile@example.com"));
    }

    @Test
    void passwordResetDoesNotRevealUnknownAccount() {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("unknown@example.com");

        when(users.findByEmail(request.getEmail()))
                .thenReturn(Optional.empty());

        PasswordResetService service = new PasswordResetService(
                users,
                mock(OtpService.class),
                email,
                new BCryptPasswordEncoder(),
                otps
        );

        assertEquals(
                "If an account exists, an OTP has been sent",
                service.forgotPassword(request)
        );

        verify(email, never())
                .sendOtpEmail(anyString(), anyString());
    }

    @Test
    void eventCreationUsesAuthenticatedCreatorAndRole() {
        User caller = user("member@example.com", "STUDENT", 1L);

        Event request = new Event();
        request.setRole("ADMIN");

        when(users.findByEmail("member@example.com"))
                .thenReturn(Optional.of(caller));

        when(events.save(any(Event.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventService service = new EventService(
                events,
                registrations,
                email,
                users
        );

        Event saved = service.createEvent(
                request,
                "member@example.com"
        );

        assertSame(caller, saved.getCreator());
        assertEquals("STUDENT", saved.getRole());
        assertEquals("PENDING", saved.getStatus());
    }

    @Test
    void eventOwnerMayUpdateButAnotherUserCannotUpdateOrDelete() {
        User owner = user("owner@example.com", "STUDENT", 1L);

        Event stored = new Event();
        stored.setCreator(owner);
        stored.setRole("STUDENT");
        stored.setTitle("original");

        Event update = new Event();
        update.setTitle("changed");

        when(events.findById(10L))
                .thenReturn(Optional.of(stored));

        when(users.findByEmail("owner@example.com"))
                .thenReturn(Optional.of(owner));

        when(users.findByEmail("other@example.com"))
                .thenReturn(
                        Optional.of(
                                user(
                                        "other@example.com",
                                        "STUDENT",
                                        2L
                                )
                        )
                );

        when(events.save(any(Event.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventService service = new EventService(
                events,
                registrations,
                email,
                users
        );

        assertThrows(
                ResponseStatusException.class,
                () -> service.updateEvent(
                        10L,
                        update,
                        "other@example.com"
                )
        );

        assertThrows(
                ResponseStatusException.class,
                () -> service.deleteEvent(
                        10L,
                        "other@example.com"
                )
        );

        assertEquals(
                "changed",
                service.updateEvent(
                        10L,
                        update,
                        "owner@example.com"
                ).getTitle()
        );

        assertEquals("STUDENT", stored.getRole());
    }

    @Test
    void connectionRejectsSelfAndEitherDirectionDuplicate() {
        User caller = user(
                "caller@example.com",
                "STUDENT",
                1L
        );

        User receiver = user(
                "receiver@example.com",
                "STUDENT",
                2L
        );

        when(currentUser.requireUser())
                .thenReturn(caller);

        ConnectionService service = new ConnectionService(
                connections,
                users,
                currentUser
        );

        ResponseStatusException self = assertThrows(
                ResponseStatusException.class,
                () -> service.request(1L)
        );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                self.getStatusCode()
        );

        when(users.findByIdInOrderByIdAsc(List.of(1L, 2L)))
                .thenReturn(List.of(caller, receiver));

        when(
                connections.findByRequester_IdAndReceiver_Id(
                        1L,
                        2L
                )
        ).thenReturn(Optional.empty());

        when(
                connections.findByRequester_IdAndReceiver_Id(
                        2L,
                        1L
                )
        ).thenReturn(Optional.of(new Connection()));

        ResponseStatusException duplicate = assertThrows(
                ResponseStatusException.class,
                () -> service.request(2L)
        );

        assertEquals(
                HttpStatus.CONFLICT,
                duplicate.getStatusCode()
        );
    }

    @Test
    void onlyConnectionRecipientCanRespond() {
        User caller = user(
                "caller@example.com",
                "STUDENT",
                1L
        );

        User receiver = user(
                "receiver@example.com",
                "STUDENT",
                2L
        );

        Connection connection = new Connection();
        connection.setReceiver(receiver);

        when(connections.findById(3L))
                .thenReturn(Optional.of(connection));

        when(currentUser.requireUser())
                .thenReturn(caller);

        ConnectionService service = new ConnectionService(
                connections,
                users,
                currentUser
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.respond(
                        3L,
                        "ACCEPTED"
                )
        );

        assertEquals(
                HttpStatus.FORBIDDEN,
                exception.getStatusCode()
        );
    }

    @Test
    void notificationCannotBeMarkedReadByAnotherUser() {
        User owner = user(
                "owner@example.com",
                "STUDENT",
                1L
        );

        User other = user(
                "other@example.com",
                "STUDENT",
                2L
        );

        Notification notification = new Notification();
        notification.setRecipient(owner);

        when(notifications.findById(5L))
                .thenReturn(Optional.of(notification));

        when(users.findByEmail("other@example.com"))
                .thenReturn(Optional.of(other));

        NotificationService service = new NotificationService(
                notifications,
                messaging,
                users
        );

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.markRead(
                        5L,
                        "other@example.com"
                )
        );

        assertEquals(
                HttpStatus.FORBIDDEN,
                exception.getStatusCode()
        );

        verify(notifications, never())
                .save(any());
    }

    @Test
    void expiredOtpIsConsumedAndRejected() {
        Otp otp = new Otp();
        otp.setExpiry(
                LocalDateTime.now().minusMinutes(1)
        );
        otp.setAttemptCount(0);
        otp.setVerified(false);

        when(
                otps.findFirstByEmailAndPurposeOrderByIdDesc(
                        "user@example.com",
                        "PASSWORD_RESET"
                )
        ).thenReturn(Optional.of(otp));

        OtpService service = new OtpService(
                otps,
                encoder
        );

        assertFalse(
                service.verifyOtp(
                        "user@example.com",
                        "123456",
                        "PASSWORD_RESET"
                )
        );

        assertNotNull(otp.getConsumedAt());

        verify(otps).save(otp);

        verify(
                encoder,
                never()
        ).matches(
                anyString(),
                anyString()
        );
    }

    private User user(
            String email,
            String role,
            Long id
    ) {
        User user = new User();

        user.setEmail(email);
        user.setRole(role);

        try {
            Field field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }

        return user;
    }
}