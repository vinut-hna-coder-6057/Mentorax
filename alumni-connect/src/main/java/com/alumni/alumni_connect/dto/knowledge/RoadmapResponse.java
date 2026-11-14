package com.alumni.alumni_connect.dto.knowledge;

import java.time.LocalDateTime;
import java.util.List;

public record RoadmapResponse(long id, String title, String goal, String evidenceStatus,
                              LocalDateTime createdAt, LocalDateTime updatedAt, List<Item> items) {
    public record Item(long id, int position, String phase, String title, String description,
                       String sourceKind, String progressStatus, List<Source> sources) {}
    public record Source(long contributionId, int version, String title, String category,
                         boolean currentlyEligible) {}
}
