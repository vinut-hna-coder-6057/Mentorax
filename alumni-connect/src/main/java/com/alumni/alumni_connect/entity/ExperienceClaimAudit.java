package com.alumni.alumni_connect.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "experience_claim_audit")
public class ExperienceClaimAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "actor_email", nullable = false, length = 255)
    private String actorEmail;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "old_status", length = 32)
    private String oldStatus;

    @Column(name = "new_status", length = 32)
    private String newStatus;

    @Column(name = "event_note", length = 500)
    private String eventNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ExperienceClaimAudit() {}

    public ExperienceClaimAudit(Long claimId, String actorEmail, String eventType,
                                String oldStatus, String newStatus, String eventNote) {
        this.claimId = claimId;
        this.actorEmail = actorEmail;
        this.eventType = eventType;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.eventNote = eventNote;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getClaimId() { return claimId; }
    public String getActorEmail() { return actorEmail; }
    public String getEventType() { return eventType; }
    public String getOldStatus() { return oldStatus; }
    public String getNewStatus() { return newStatus; }
    public String getEventNote() { return eventNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
