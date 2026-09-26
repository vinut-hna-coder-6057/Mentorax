package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {

        this.authService = authService;
    }

    // =====================================
    // SIGNUP
    // =====================================

    @PostMapping("/signup")
    public String signup(
            @RequestBody User user
    ) {

        return authService.signup(user);
    }

    // =====================================
    // LOGIN
    // =====================================

    @PostMapping("/login")
    public Object login(
            @RequestBody User user
    ) {

        return authService.login(user);
    }

    // =====================================
    // APPROVE USER
    // =====================================

    @PutMapping("/approve/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public User approveUser(
            @PathVariable Long id
    ) {

        return authService.approveUser(id);
    }
}
