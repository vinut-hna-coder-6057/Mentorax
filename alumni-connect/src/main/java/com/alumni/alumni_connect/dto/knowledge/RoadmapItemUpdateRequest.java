package com.alumni.alumni_connect.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RoadmapItemUpdateRequest(@NotBlank @Size(max = 180) String title,
                                       @NotBlank @Size(max = 1000) String description,
                                       @NotNull ProgressStatus status) {
    public enum ProgressStatus { NOT_STARTED, IN_PROGRESS, COMPLETED }
}
