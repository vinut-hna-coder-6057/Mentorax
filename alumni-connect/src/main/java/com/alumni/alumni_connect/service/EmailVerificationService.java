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

    private final UserRepository userRepository;
    private final OtpService otpService;

    public EmailVerificationService(
            UserRepository userRepository,
            OtpService otpService
    ) {
        this.userRepository = userRepository;
        this.otpService = otpService;
    }

    @Transactional
    public String verifyEmail(VerifyOtpRequest request) {

        User user = userRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (user.isEmailVerified()) {
            return "Email already verified";
        }

        boolean valid = otpService.verifyOtp(
                request.getEmail(),
                request.getOtp(),
                "EMAIL_VERIFICATION"
        );

        if (!valid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired OTP"
            );
        }

        user.setEmailVerified(true);
        userRepository.save(user);

        return "Email verified successfully";
    }
}