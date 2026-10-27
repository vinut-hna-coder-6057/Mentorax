package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.ExperienceClaimAudit;

import java.time.LocalDateTime;

public record ExperienceClaimAuditResponse(
        String actorEmail,
        String eventType,
        String oldStatus,
        String newStatus,
        String eventNote,
        LocalDateTime createdAt
) {
    public static ExperienceClaimAuditResponse from(ExperienceClaimAudit event) {
        return new ExperienceClaimAuditResponse(event.getActorEmail(), event.getEventType(),
                event.getOldStatus(), event.getNewStatus(), event.getEventNote(), event.getCreatedAt());
    }
}
