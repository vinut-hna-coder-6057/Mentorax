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
import jakarta.validation.Valid;

@RestController

public class PasswordResetController {

    // =====================================
    // SERVICE
    // =====================================

    private final PasswordResetService
            passwordResetService;

    // =====================================
    // CONSTRUCTOR
    // =====================================

    public PasswordResetController(

            PasswordResetService
                    passwordResetService

    ) {

        this.passwordResetService =
                passwordResetService;
    }

    // =====================================
    // SEND OTP
    // =====================================

    @PostMapping(
            "/forgot-password"
    )

    public String forgotPassword(

            @Valid @RequestBody
            ForgotPasswordRequest request

    ) {

        return passwordResetService
                .forgotPassword(request);
    }

    // =====================================
    // VERIFY OTP
    // =====================================

    @PostMapping(
            "/verify-otp"
    )

    public String verifyOtp(

            @Valid @RequestBody
            VerifyOtpRequest request

    ) {

        return passwordResetService
                .verifyOtp(request);
    }

    // =====================================
    // RESET PASSWORD
    // =====================================

    @PostMapping(
            "/reset-password"
    )

    public String resetPassword(

            @Valid @RequestBody
            ResetPasswordRequest request

    ) {

        return passwordResetService
                .resetPassword(request);
    }
}
