package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.VerifyOtpRequest;
import com.alumni.alumni_connect.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(
            EmailVerificationService emailVerificationService
    ) {
        this.emailVerificationService = emailVerificationService;
    }

    @PostMapping("/verify-email")
    public String verifyEmail(
            @Valid @RequestBody VerifyOtpRequest request
    ) {
        return emailVerificationService.verifyEmail(request);
    }
}