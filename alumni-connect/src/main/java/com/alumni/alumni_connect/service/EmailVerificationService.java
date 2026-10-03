package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.VerifyOtpRequest;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailVerificationService {
    public record VerificationResult(boolean successful, String message) {}


    private final UserRepository userRepository;
    private final OtpService otpService;
    private final EmailService emailService;

    public EmailVerificationService(
            UserRepository userRepository,
            OtpService otpService,
            EmailService emailService
    ) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.emailService = emailService;
    }

    @Transactional
    public VerificationResult verifyEmail(VerifyOtpRequest request) {

        User user = userRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (user.isEmailVerified()) {
            return new VerificationResult(true, "Email already verified");
        }

        boolean valid = otpService.verifyOtp(
                request.getEmail(),
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

    public String resendVerification(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user != null && !user.isEmailVerified()) {
            String otp = otpService.generateOtp(email, "EMAIL_VERIFICATION");
            emailService.sendEmailVerificationOtp(email, otp);
        }
        return "If this account needs verification, a code has been sent.";
    }
}