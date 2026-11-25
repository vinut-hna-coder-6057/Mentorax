package com.alumni.alumni_connect.security;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtFilterOtpModeTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void otpFreeModeAllowsValidJwtForApprovedUnverifiedStudent() throws Exception {
        UserRepository users = mock(UserRepository.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User student = student();
        when(jwt.validateToken("test.jwt.token")).thenReturn(true);
        when(jwt.extractEmail("test.jwt.token")).thenReturn(student.getEmail());
        when(jwt.extractRole("test.jwt.token")).thenReturn("STUDENT");
        when(users.findByEmail(student.getEmail())).thenReturn(Optional.of(student));

        JwtFilter filter = new JwtFilter(jwt, users);
        ReflectionTestUtils.setField(filter, "emailVerificationRequired", false);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> { };

        filter.doFilterInternal(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void defaultModeContinuesToRejectUnverifiedStudentJwt() throws Exception {
        UserRepository users = mock(UserRepository.class);
        JwtUtil jwt = mock(JwtUtil.class);
        User student = student();
        when(jwt.validateToken("test.jwt.token")).thenReturn(true);
        when(jwt.extractEmail("test.jwt.token")).thenReturn(student.getEmail());
        when(jwt.extractRole("test.jwt.token")).thenReturn("STUDENT");
        when(users.findByEmail(student.getEmail())).thenReturn(Optional.of(student));

        JwtFilter filter = new JwtFilter(jwt, users);
        filter.doFilterInternal(request(), new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> { });

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test.jwt.token");
        return request;
    }

    private static User student() {
        User student = new User("Test Student", "student@example.test", "stored-password-hash",
                "STUDENT", "APPROVED");
        student.setEmailVerified(false);
        return student;
    }
}
