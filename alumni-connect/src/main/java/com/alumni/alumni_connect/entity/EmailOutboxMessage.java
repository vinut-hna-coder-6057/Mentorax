package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "email_outbox",
        indexes = {
                @Index(
                        name = "idx_email_outbox_due",
                        columnList = "status,next_attempt_at,lease_until,expires_at"),
                @Index(name = "idx_email_outbox_expiry", columnList = "expires_at")
        })
public class EmailOutboxMessage {
    public static final String PENDING = "PENDING";
    public static final String PROCESSING = "PROCESSING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String recipient;

    @Column(name = "encrypted_otp", nullable = false, length = 512)
    private String encryptedOtp;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected EmailOutboxMessage() {}

    public EmailOutboxMessage(
            String recipient,
            String encryptedOtp,
            LocalDateTime now,
            LocalDateTime expiresAt) {
        this.recipient = recipient;
        this.encryptedOtp = encryptedOtp;
        this.status = PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getEncryptedOtp() {
        return encryptedOtp;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public String getStatus() {
        return status;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void claim(LocalDateTime leaseUntil) {
        status = PROCESSING;
        this.leaseUntil = leaseUntil;
        attemptCount++;
    }

    public void deferUntil(LocalDateTime nextAttemptAt) {
        status = PENDING;
        this.nextAttemptAt = nextAttemptAt;
        leaseUntil = null;
    }
}
