package com.alumni.alumni_connect.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExperienceClaimAppealRequest(
        @NotBlank @Size(max = 1000) String note
) {
}
