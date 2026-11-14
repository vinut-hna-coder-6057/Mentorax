package com.alumni.alumni_connect.dto.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record KnowledgeContributionRequest(
        @NotNull Category category,
        @NotBlank @Size(max = 160) String title,
        @NotBlank @Size(max = 6000) String narrative,
        @Size(max = 150) String companyName,
        @Size(max = 150) String jobRole,
        @Min(1950) @Max(2100) Integer experienceYear,
        @NotNull @Size(max = 300) List<@Valid Entry> entries) {

    public enum Category {
        INTERVIEW_EXPERIENCE, PREPARATION_STRATEGY, PROJECT_EXPERIENCE,
        CAREER_JOURNEY, PROFESSIONAL_ADVICE, MENTORSHIP_GUIDANCE
    }

    public enum EntryType {
        TECHNOLOGY, INTERVIEW_ROUND, INTERVIEW_TOPIC, INTERVIEW_QUESTION,
        RESOURCE, CHALLENGE, LESSON, ROLE_TRANSITION
    }

    public record Entry(
            @NotNull EntryType type,
            @Min(0) @Max(100) Integer position,
            @Min(0) @Max(100) Integer parentPosition,
            @Size(max = 1000) String value,
            @Size(max = 2000) String detail,
            @Size(max = 500) String url,
            @Size(max = 150) String fromRole,
            @Size(max = 150) String toRole,
            @Min(1950) @Max(2100) Integer year) {}
}
