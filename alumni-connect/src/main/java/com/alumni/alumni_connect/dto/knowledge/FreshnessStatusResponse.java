package com.alumni.alumni_connect.dto.knowledge;

import java.time.LocalDateTime;

public record FreshnessStatusResponse(long contributionId, int intervalDays, boolean remindersEnabled,
                                      LocalDateTime lastConfirmedAt, LocalDateTime reminderDueAt,
                                      LocalDateTime lastReminderSentAt) {}
