package com.alumni.alumni_connect.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MessageRequest(
        @NotBlank @Email @Size(max = 255) String receiverEmail,
        @NotBlank @Size(max = 2000) String content
) {}
