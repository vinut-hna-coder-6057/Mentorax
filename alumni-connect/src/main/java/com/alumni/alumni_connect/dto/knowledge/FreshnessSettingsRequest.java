package com.alumni.alumni_connect.dto.knowledge;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record FreshnessSettingsRequest(@Min(90) @Max(180) int intervalDays, @NotNull Boolean remindersEnabled) {}
