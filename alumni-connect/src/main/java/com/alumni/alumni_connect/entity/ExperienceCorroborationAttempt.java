package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "experience_corroboration_attempts")
public class ExperienceCorroborationAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "provider_id", nullable = false, length = 100)
    private String providerId;

    @Column(name = "verification_method", nullable = false, length = 100)
    private String verificationMethod;

    @Column(name = "request_at", nullable = false)
    private LocalDateTime requestAt;

    @Column(name = "response_at")
    private LocalDateTime responseAt;

    @Column(name = "result", nullable = false, length = 32)
    private String result;

    @Column(name = "reference_identifier", length = 255)
    private String referenceIdentifier;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "independently_authenticated", nullable = false)
    private boolean independentlyAuthenticated;

    protected ExperienceCorroborationAttempt() {}

    public ExperienceCorroborationAttempt(Long claimId, String providerId,
                                          String verificationMethod, LocalDateTime requestAt) {
        this.claimId = claimId;
        this.providerId = providerId;
        this.verificationMethod = verificationMethod;
        this.requestAt = requestAt;
        this.result = "PENDING";
    }

    public Long getId() { return id; }
    public Long getClaimId() { return claimId; }
    public String getProviderId() { return providerId; }
    public String getVerificationMethod() { return verificationMethod; }
    public LocalDateTime getRequestAt() { return requestAt; }
    public LocalDateTime getResponseAt() { return responseAt; }
    public void setResponseAt(LocalDateTime responseAt) { this.responseAt = responseAt; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReferenceIdentifier() { return referenceIdentifier; }
    public void setReferenceIdentifier(String referenceIdentifier) { this.referenceIdentifier = referenceIdentifier; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public boolean isIndependentlyAuthenticated() { return independentlyAuthenticated; }
    public void setIndependentlyAuthenticated(boolean independentlyAuthenticated) {
        this.independentlyAuthenticated = independentlyAuthenticated;
    }
}
