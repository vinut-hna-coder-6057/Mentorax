package com.alumni.alumni_connect;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final CurrentUserService service = new CurrentUserService(users);

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void ownerMayModifyOnlyOwnProfile() {
        User user = new User();
        user.setEmail("owner@example.test");
        setId(user, 7L);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.getEmail(), null,
                List.of(new SimpleGrantedAuthority("ROLE_STUDENT"))));
        assertDoesNotThrow(() -> service.requireOwnerOrAdmin(7L));
        assertThrows(ResponseStatusException.class, () -> service.requireOwnerOrAdmin(8L));
    }

    @Test
    void unauthenticatedCallerIsRejected() {
        assertThrows(ResponseStatusException.class, () -> service.requireUser());
    }

    /* The entity intentionally has no public id setter; a test fixture normally persists it. */
    private void setId(User user, Long id) {
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}

