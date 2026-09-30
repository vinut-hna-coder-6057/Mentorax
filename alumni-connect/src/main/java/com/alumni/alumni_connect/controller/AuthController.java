package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
public class AuthController {

    private final AuthService authService;
    private final RequestRateLimiter rateLimiter;

    @Autowired
    public AuthController(
            AuthService authService, RequestRateLimiter rateLimiter
    ) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    /** Keeps direct Java callers source compatible; this overload is not an HTTP handler. */
    public AuthController(AuthService authService) { this(authService, new RequestRateLimiter()); }

    // =====================================
    // SIGNUP
    // =====================================

    @PostMapping("/signup")
    public String signup(@Valid @RequestBody SignupRequest request, HttpServletRequest http) {
        rateLimiter.check("otp", http.getRemoteAddr(), request.email());
        return authService.signup(request);
    }

    // =====================================
    // LOGIN
    // =====================================

    @PostMapping("/login")
    public Object login(
            @Valid @RequestBody LoginRequest user, HttpServletRequest http
    ) {
        rateLimiter.check("login", http.getRemoteAddr(), user.email());
        return authService.login(user);
    }

    public Object login(User user) { return authService.login(user); }
    public String signup(User user) { return authService.signup(user); }

    // =====================================
    // APPROVE USER
    // =====================================

    @PutMapping("/approve/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public UserProfileResponse approveUser(
            @PathVariable Long id
    ) {
        return UserProfileResponse.from(
                authService.approveUser(id)
        );
    }
}
