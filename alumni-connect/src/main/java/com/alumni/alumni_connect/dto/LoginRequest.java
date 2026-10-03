package com.alumni.alumni_connect.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.alumni.alumni_connect.util.EmailAddress;
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 128) String password,
        @Pattern(regexp = "(?i)STUDENT|ALUMNI|ADMIN") String role) {

    public LoginRequest {
        email = EmailAddress.normalize(email);
    }

    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
