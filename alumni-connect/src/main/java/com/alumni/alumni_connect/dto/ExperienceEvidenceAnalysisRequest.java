package com.alumni.alumni_connect.dto;

public record ExperienceEvidenceAnalysisRequest(
        String company,
        String jobTitle,
        String startMonth,
        String endMonth,
        String summary
) {
}
