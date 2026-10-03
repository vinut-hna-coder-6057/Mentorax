package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.VerifyOtpRequest;
import com.alumni.alumni_connect.dto.ForgotPasswordRequest;
import com.alumni.alumni_connect.service.EmailVerificationService;
import com.alumni.alumni_connect.service.RequestRateLimiter;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

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
        EmailVerificationService.VerificationResult result =
                emailVerificationService.verifyEmail(request);
        if (!result.successful()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, result.message());
        }
        return result.message();
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<String> resendVerification(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest http
    ) {
        rateLimiter.check("otp", http.getRemoteAddr(), request.getEmail());
        return ResponseEntity.accepted()
                .body(emailVerificationService.resendVerification(request.getEmail()));
    }
}
