package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.LoginRequest;
import com.alumni.alumni_connect.dto.SignupRequest;
import com.alumni.alumni_connect.entity.AlumniProfile;
import com.alumni.alumni_connect.entity.StudentProfile;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporaryOtpFreeAuthenticationTest {
    private UserRepository users;
    private StudentProfileRepository students;
    private AlumniProfileRepository alumni;
    private OtpService otpService;
    private EmailOutboxService outbox;
    private PasswordEncoder passwordEncoder;
    private SignupTransactionService signup;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        students = mock(StudentProfileRepository.class);
        alumni = mock(AlumniProfileRepository.class);
        otpService = mock(OtpService.class);
        outbox = mock(EmailOutboxService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        signup = new SignupTransactionService(users, passwordEncoder, students, alumni, otpService, outbox);
        ReflectionTestUtils.setField(signup, "emailVerificationRequired", false);
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void studentSignupCreatesApprovedProfileWithHashWithoutCallingOtpServices() {
        doThrow(new AssertionError("OTP email delivery must not be called in OTP-free signup mode"))
                .when(outbox).enqueueOtp(anyString(), anyString(), anyString());
        SignupRequest request = request("STUDENT", "student@example.test");

        signup.createAccount(request);

        var user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertEquals("STUDENT", user.getValue().getRole());
        assertEquals("APPROVED", user.getValue().getStatus());
        assertFalse(user.getValue().isEmailVerified());
        assertTrue(passwordEncoder.matches("TestPassword123", user.getValue().getPassword()));
        verify(students).save(any(StudentProfile.class));
        verify(alumni, never()).save(any(AlumniProfile.class));
        verify(otpService, never()).generateOtp(anyString(), anyString());
        verify(outbox, never()).enqueueOtp(anyString(), anyString(), anyString());
    }

    @Test
    void alumniSignupKeepsAdministratorApprovalAndCreatesProfileWithoutOtp() {
        signup.createAccount(request("ALUMNI", "alumni@example.test"));

        var user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertEquals("PENDING", user.getValue().getStatus());
        assertFalse(user.getValue().isEmailVerified());
        verify(alumni).save(any(AlumniProfile.class));
        verify(students, never()).save(any(StudentProfile.class));
        verify(otpService, never()).generateOtp(anyString(), anyString());
        verify(outbox, never()).enqueueOtp(anyString(), anyString(), anyString());
    }

    @Test
    void duplicateSignupDoesNotCreateAnotherAccountOrQueueOtp() {
        User existing = user("STUDENT", "APPROVED", false, "existing-hash");
        existing.setEmail("existing@example.test");
        when(users.findByEmailIgnoreCase("existing@example.test"))
                .thenReturn(Optional.of(existing));

        assertThrows(ResponseStatusException.class,
                () -> signup.createAccount(request("STUDENT", "existing@example.test")));

        verify(users, never()).save(any(User.class));
        verify(students, never()).save(any(StudentProfile.class));
        verify(alumni, never()).save(any(AlumniProfile.class));
        verify(otpService, never()).generateOtp(anyString(), anyString());
        verify(outbox, never()).enqueueOtp(anyString(), anyString(), anyString());
    }

    @Test
    void defaultVerificationRequiredModeContinuesToHashPasswordAndQueueOtp() {
        SignupTransactionService verificationRequiredSignup = new SignupTransactionService(
                users, passwordEncoder, students, alumni, otpService, outbox);
        when(otpService.generateOtp("student@example.test", "EMAIL_VERIFICATION"))
                .thenReturn("123456");

        verificationRequiredSignup.createAccount(request("STUDENT", "student@example.test"));

        var user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(user.capture());
        assertEquals("PENDING", user.getValue().getStatus());
        assertFalse(user.getValue().isEmailVerified());
        assertTrue(passwordEncoder.matches("TestPassword123", user.getValue().getPassword()));
        verify(otpService).generateOtp("student@example.test", "EMAIL_VERIFICATION");
        verify(outbox).enqueueOtp("student@example.test", "EMAIL_VERIFICATION", "123456");
    }

    @Test
    void studentWithExistingPasswordCanLoginWithoutEmailVerificationAndReceivesJwt() {
        User existing = user("STUDENT", "PENDING", false,
                passwordEncoder.encode("ExistingPassword123"));
        when(users.findByEmailIgnoreCase("student@example.test")).thenReturn(Optional.of(existing));
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.generateToken("student@example.test", "STUDENT")).thenReturn("signed.jwt.token");
        AuthService auth = authService(users, jwt, false);

        assertEquals("signed.jwt.token", auth.login(
                new LoginRequest("student@example.test", "ExistingPassword123", "STUDENT")));
        assertEquals("APPROVED", existing.getStatus());
        assertFalse(existing.isEmailVerified());
        verify(users).save(existing);
        verify(jwt).generateToken("student@example.test", "STUDENT");
    }

    @Test
    void incorrectPasswordStillFailsInOtpFreeMode() {
        User existing = user("STUDENT", "APPROVED", false,
                passwordEncoder.encode("CorrectPassword123"));
        when(users.findByEmailIgnoreCase("student@example.test")).thenReturn(Optional.of(existing));
        AuthService auth = authService(users, mock(JwtUtil.class), false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> auth.login(new LoginRequest("student@example.test", "WrongPassword123", "STUDENT")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void missingPasswordHashCannotBypassAuthentication() {
        User existing = user("STUDENT", "APPROVED", false, null);
        when(users.findByEmailIgnoreCase("student@example.test")).thenReturn(Optional.of(existing));
        BCryptPasswordEncoder encoder = mock(BCryptPasswordEncoder.class);
        AuthService auth = new AuthService(users, encoder, mock(JwtUtil.class), alumni, mock(SignupTransactionService.class));
        ReflectionTestUtils.setField(auth, "emailVerificationRequired", false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> auth.login(new LoginRequest("student@example.test", "AnyPassword123", "STUDENT")));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
        verify(encoder, never()).matches(anyString(), any());
    }

    @Test
    void alumniStillRequiresAdministratorApproval() {
        User existing = user("ALUMNI", "PENDING", false,
                passwordEncoder.encode("ExistingPassword123"));
        when(users.findByEmailIgnoreCase("student@example.test")).thenReturn(Optional.of(existing));
        AuthService auth = authService(users, mock(JwtUtil.class), false);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> auth.login(new LoginRequest("student@example.test", "ExistingPassword123", "ALUMNI")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals("PENDING", existing.getStatus());
    }

    @Test
    void signupReturnsOtpFreeMessageAndDuplicateResponseRemainsNonEnumerating() {
        SignupTransactionService transactions = mock(SignupTransactionService.class);
        AuthService auth = new AuthService(
                users, mock(BCryptPasswordEncoder.class), mock(JwtUtil.class), alumni, transactions);
        ReflectionTestUtils.setField(auth, "emailVerificationRequired", false);

        assertEquals("If this address is eligible, you can sign in with your email and password.",
                auth.signup(request("STUDENT", "student@example.test")));
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "duplicate"))
                .when(transactions).createAccount(any(SignupRequest.class));
        assertEquals("If this address is eligible, you can sign in with your email and password.",
                auth.signup(request("STUDENT", "student@example.test")));
    }

    private AuthService authService(UserRepository repository, JwtUtil jwt, boolean verificationRequired) {
        AuthService auth = new AuthService(repository, new BCryptPasswordEncoder(), jwt, alumni, signup);
        ReflectionTestUtils.setField(auth, "emailVerificationRequired", verificationRequired);
        return auth;
    }

    private static User user(String role, String status, boolean verified, String passwordHash) {
        User user = new User("Test User", "student@example.test", passwordHash, role, status);
        user.setEmailVerified(verified);
        return user;
    }

    private static SignupRequest request(String role, String email) {
        return new SignupRequest("Test User", email, "TestPassword123", role,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
