package com.alumni.alumni_connect.dto.knowledge;

import java.time.LocalDateTime;
import java.util.List;

public record CompanionMessageResponse(long id, String speaker, String content,
                                       String evidenceStatus, String answerMode,
                                       LocalDateTime createdAt, List<SourceRef> sources,
                                       String followUpReason, List<Mentor> mentors) {
    public record SourceRef(long contributionId, int version, String title, String category,
                            boolean currentlyEligible) {}
    public record Mentor(long id, String displayName, String role, String company,
                         double relevance, String uncertainty) {}
}
