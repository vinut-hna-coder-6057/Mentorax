package com.alumni.alumni_connect.dto.knowledge;

import java.util.List;

public record KnowledgeRetrievalResponse(String query, int page, int size, boolean hasMore,
                                         List<Source> sources) {
    public record Source(long contributionId, int version, String category, String title,
                         String excerpt, double relevance, List<String> matchedFields,
                         String attribution, String companyName, String jobRole) {}
}
