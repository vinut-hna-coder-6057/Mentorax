package com.alumni.alumni_connect.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ExperienceClaimReviewRequest(
        @NotBlank @Pattern(regexp = "NEEDS_INFO|REJECTED|PENDING") String status,
        @Size(max = 500) String note
) {
}
