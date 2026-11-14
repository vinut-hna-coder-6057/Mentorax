package com.alumni.alumni_connect.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RoadmapCreateRequest(@NotBlank @Size(max = 500) String goal) {}
