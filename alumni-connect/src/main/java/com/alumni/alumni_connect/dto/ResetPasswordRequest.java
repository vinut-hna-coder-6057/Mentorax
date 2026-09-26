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
import jakarta.validation.constraints.Size;

public class ResetPasswordRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, max = 128)
    private String newPassword;

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
    // GET PASSWORD
    // =====================================

    public String getNewPassword() {

        return newPassword;
    }

    // =====================================
    // SET PASSWORD
    // =====================================

    public void setNewPassword(

            String newPassword

    ) {

        this.newPassword = newPassword;
    }
}
