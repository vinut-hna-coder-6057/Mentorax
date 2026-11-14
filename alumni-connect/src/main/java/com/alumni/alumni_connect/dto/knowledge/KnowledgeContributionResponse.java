package com.alumni.alumni_connect.dto.knowledge;

import java.time.LocalDateTime;
import java.util.List;

public record KnowledgeContributionResponse(
        long id, int version, Integer publishedVersion, String category, String title, String narrative,
        String companyName, String jobRole, Integer experienceYear,
        String status, boolean studentVisibilityConsent, boolean aiRetrievalConsent,
        LocalDateTime createdAt, LocalDateTime updatedAt, LocalDateTime publishedAt,
        List<Entry> entries) {
    public record Entry(String type, int position, Integer parentPosition, String value,
                        String detail, String url, String fromRole, String toRole, Integer year) {}
}
