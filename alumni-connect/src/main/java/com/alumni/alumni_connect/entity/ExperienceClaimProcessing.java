package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "experience_claim_processing")
public class ExperienceClaimProcessing {
    @Id
    @Column(name = "claim_id")
    private Long claimId;

    @Column(name = "evidence_version", nullable = false)
    private int evidenceVersion;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ExperienceClaimProcessing() {}

    public ExperienceClaimProcessing(Long claimId, int evidenceVersion, LocalDateTime now) {
        this.claimId = claimId;
        this.evidenceVersion = evidenceVersion;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getClaimId() { return claimId; }
    public int getEvidenceVersion() { return evidenceVersion; }
    public void setEvidenceVersion(int evidenceVersion) { this.evidenceVersion = evidenceVersion; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public LocalDateTime getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(LocalDateTime leaseUntil) { this.leaseUntil = leaseUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
