package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import com.alumni.alumni_connect.dto.ResetAuthorizationResponse;
import java.time.LocalDateTime;

@Service
public class PasswordResetService {

    // =====================================
    // DEPENDENCIES
    // =====================================

    private final UserRepository userRepository;

    private final OtpService otpService;

    private final EmailOutboxService emailOutboxService;

    private final PasswordEncoder passwordEncoder;

    private final OtpRepository otpRepository;

    // =====================================
    // CONSTRUCTOR
    // =====================================

    public PasswordResetService(

            UserRepository userRepository,

            OtpService otpService,

            EmailOutboxService emailOutboxService,

            PasswordEncoder passwordEncoder,

            OtpRepository otpRepository

    ) {

        this.userRepository =
                userRepository;

        this.otpService =
                otpService;

        this.emailOutboxService = emailOutboxService;

        this.passwordEncoder =
                passwordEncoder;

        this.otpRepository =
                otpRepository;
    }

    // =====================================
    // SEND OTP
    // =====================================

    @Transactional
    public String forgotPassword(

            ForgotPasswordRequest request

    ) {

        // CHECK USER

        User user =
                userRepository.findByEmailIgnoreCase(
                        request.getEmail()
                ).orElse(null);

        if (user == null) {
            return "If an account exists, a code will be sent shortly";
        }

        // GENERATE OTP
        String recipient = user.getEmail();
        String otp = otpService.generateOtp(
                recipient,
                "PASSWORD_RESET"
        );
        emailOutboxService.enqueueOtp(recipient, "PASSWORD_RESET", otp);

        return "If an account exists, a code will be sent shortly";
    }

    // =====================================
    // VERIFY OTP
    // =====================================

    public ResetAuthorizationResponse verifyOtp(VerifyOtpRequest request) {
        return otpService.verifyPasswordResetOtp(request.getEmail(), request.getOtp())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid or expired OTP"));
    }

    // =====================================
    // RESET PASSWORD
    // =====================================

    @Transactional
    public String resetPassword(ResetPasswordRequest request) {
        if (request.getNewPassword() == null || request.getNewPassword().length() < 8
                || request.getNewPassword().length() > 128) {
        throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Password does not meet requirements"
        );
    }

        User user =
                userRepository.findByEmailIgnoreCase(
                        request.getEmail()
                ).orElse(null);

        if (user == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired reset authorization"
            );
        }
        if (request.getResetToken() == null || request.getResetToken().isBlank()
                || request.getResetToken().length() > 64) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired reset authorization");
        }

        int consumed = otpRepository.consumeResetAuthorization(
                request.getEmail(),
                otpService.hashResetToken(request.getResetToken()),
                LocalDateTime.now());
        if (consumed != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired reset authorization");
        }

        user.setPassword(
                passwordEncoder.encode(
                        request.getNewPassword()
                )
        );
        userRepository.save(user);
        return "Password reset successful";
    }
}
