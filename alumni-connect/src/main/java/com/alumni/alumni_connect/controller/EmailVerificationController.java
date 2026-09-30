package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.VerifyOtpRequest;
import com.alumni.alumni_connect.service.EmailVerificationService;
import com.alumni.alumni_connect.service.RequestRateLimiter;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;
    private final RequestRateLimiter rateLimiter;

    public EmailVerificationController(
            EmailVerificationService emailVerificationService, RequestRateLimiter rateLimiter
    ) {
        this.emailVerificationService = emailVerificationService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/verify-email")
    public String verifyEmail(
            @Valid @RequestBody VerifyOtpRequest request, HttpServletRequest http
    ) {
        rateLimiter.check("otp", http.getRemoteAddr(), request.getEmail());
        return emailVerificationService.verifyEmail(request);
    }
}
