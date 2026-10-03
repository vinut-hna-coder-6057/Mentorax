package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.VerifyOtpRequest;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailVerificationService {
    public record VerificationResult(boolean successful, String message) {}


    private final UserRepository userRepository;
    private final OtpService otpService;
    private final EmailOutboxService emailOutboxService;
    @Value("${app.email-verification.required:false}")
    private boolean emailVerificationRequired = true;

    public EmailVerificationService(
            UserRepository userRepository,
            OtpService otpService,
            EmailOutboxService emailOutboxService
    ) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.emailOutboxService = emailOutboxService;
    }

    @Transactional
    public VerificationResult verifyEmail(VerifyOtpRequest request) {

        User user = userRepository
                .findByEmailIgnoreCase(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (user.isEmailVerified()) {
            return new VerificationResult(true, "Email already verified");
        }

        boolean valid = otpService.verifyOtp(
                user.getEmail(),
                request.getOtp(),
                "EMAIL_VERIFICATION"
        );

        if (!valid) {
            return new VerificationResult(false, "Invalid or expired OTP");
        }

        user.setEmailVerified(true);
        userRepository.save(user);

        return new VerificationResult(true, "Email verified successfully");
    }

    @Transactional
    public String resendVerification(String email) {
        if (!emailVerificationRequired) {
            return "Email verification is temporarily disabled.";
        }
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && !user.isEmailVerified()) {
            String recipient = user.getEmail();
            String otp = otpService.generateOtp(recipient, "EMAIL_VERIFICATION");
            emailOutboxService.enqueueVerification(recipient, otp);
        }
        return "If this account needs verification, a code will be sent shortly.";
    }
}