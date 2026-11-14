package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.ExperienceClaimType;

public record ExperienceEvidenceAnalysisRequest(
        Long evidenceId,
        Integer evidenceVersion,
        String sha256,
        ExperienceClaimType claimType,
        String company,
        String jobTitle,
        String startMonth,
        String endMonth,
        String summary,
        String employeeName
) {
}
