package com.alumni.alumni_connect.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

public record ExperienceClaimRequest(
        @NotBlank @Size(max = 150) String company,
        @NotBlank @Size(max = 150) String jobTitle,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F-]{36}$") String requestId,
        @jakarta.validation.constraints.Pattern(regexp = "^$|^\\d{4}-(0[1-9]|1[0-2])$")
        @Size(max = 7) String startMonth,
        @jakarta.validation.constraints.Pattern(regexp = "^$|^\\d{4}-(0[1-9]|1[0-2])$")
        @Size(max = 7) String endMonth,
        @NotBlank @Size(max = 2000) String summary,
        @Size(max = 2000) String evidenceSummary
) {
}
