package com.alumni.alumni_connect.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(@NotBlank @Email @Size(max=255) String email, @NotBlank @Size(max=128) String password) {}
