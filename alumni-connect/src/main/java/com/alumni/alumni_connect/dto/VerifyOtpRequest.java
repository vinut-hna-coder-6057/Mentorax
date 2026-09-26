package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class VerifyOtpRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Pattern(regexp = "\\d{6}")
    private String otp;

    // =====================================
    // GET EMAIL
    // =====================================

    public String getEmail() {

        return email;
    }

    // =====================================
    // SET EMAIL
    // =====================================

    public void setEmail(

            String email

    ) {

        this.email = email;
    }

    // =====================================
    // GET OTP
    // =====================================

    public String getOtp() {

        return otp;
    }

    // =====================================
    // SET OTP
    // =====================================

    public void setOtp(

            String otp

    ) {

        this.otp = otp;
    }
}
