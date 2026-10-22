package com.alumni.alumni_connect.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MentorDiscoveryRequest(
        @NotBlank @Size(max = 500) String goal
) {}
