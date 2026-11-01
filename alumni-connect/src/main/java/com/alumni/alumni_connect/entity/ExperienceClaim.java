package com.alumni.alumni_connect.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "experience_claims", uniqueConstraints =
        @UniqueConstraint(name = "uk_experience_claim_user_request", columnNames = {"user_id", "request_id"}))
public class ExperienceClaim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 150)
    private String company;

    @Column(name = "job_title", nullable = false, length = 150)
    private String jobTitle;

    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "start_month", length = 32)
    private String startMonth;

    @Column(name = "end_month", length = 32)
    private String endMonth;

    @Column(nullable = false, length = 2000)
    private String summary;

    @Column(name = "evidence_summary", length = 2000)
    private String evidenceSummary;

    @Column(name = "evidence_hash", length = 128)
    private String evidenceHash;

    @Column(name = "evidence_storage_key", length = 64)
    private String evidenceStorageKey;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "encrypted_evidence", columnDefinition = "MEDIUMBLOB")
    private byte[] encryptedEvidence;

    @Column(name = "evidence_file_name", length = 255)
    private String evidenceFileName;

    @Column(name = "evidence_media_type", length = 100)
    private String evidenceMediaType;

    @Column(name = "evidence_size")
    private Long evidenceSize;

    @Column(name = "evidence_uploaded_at")
    private LocalDateTime evidenceUploadedAt;

    @Column(name = "evidence_version", nullable = false)
    private int evidenceVersion;

    @Column(nullable = false, length = 32)
    private String status = "PENDING";

    @Column(name = "ai_status", length = 32)
    private String aiStatus = "PENDING";

    @Column(name = "ai_findings", columnDefinition = "TEXT")
    private String aiFindings;

    @Column(name = "analysis_method", nullable = false, length = 32)
    private String analysisMethod = "NOT_RUN";

    @Column(name = "ocr_status", nullable = false, length = 32)
    private String ocrStatus = "NOT_RUN";

    @Column(name = "decision_explanation", columnDefinition = "TEXT")
    private String decisionExplanation;

    @Column(name = "requested_evidence", columnDefinition = "TEXT")
    private String requestedEvidence;

    @Column(name = "decision_at")
    private LocalDateTime decisionAt;

    @Column(name = "corroboration_status", nullable = false, length = 32)
    private String corroborationStatus = "NOT_CHECKED";

    @Column(name = "appeal_note", columnDefinition = "TEXT")
    private String appealNote;

    @Column(name = "reviewer_email", length = 255)
    private String reviewerEmail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getStartMonth() {
        return startMonth;
    }

    public void setStartMonth(String startMonth) {
        this.startMonth = startMonth;
    }

    public String getEndMonth() {
        return endMonth;
    }

    public void setEndMonth(String endMonth) {
        this.endMonth = endMonth;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getEvidenceSummary() {
        return evidenceSummary;
    }

    public void setEvidenceSummary(String evidenceSummary) {
        this.evidenceSummary = evidenceSummary;
    }

    public String getEvidenceHash() {
        return evidenceHash;
    }

    public void setEvidenceHash(String evidenceHash) {
        this.evidenceHash = evidenceHash;
    }

    public String getEvidenceStorageKey() {
        return evidenceStorageKey;
    }

    public void setEvidenceStorageKey(String evidenceStorageKey) {
        this.evidenceStorageKey = evidenceStorageKey;
    }

    public byte[] getEncryptedEvidence() {
        return encryptedEvidence;
    }

    public void setEncryptedEvidence(byte[] encryptedEvidence) {
        this.encryptedEvidence = encryptedEvidence;
    }

    public String getEvidenceFileName() {
        return evidenceFileName;
    }

    public void setEvidenceFileName(String evidenceFileName) {
        this.evidenceFileName = evidenceFileName;
    }

    public String getEvidenceMediaType() {
        return evidenceMediaType;
    }

    public void setEvidenceMediaType(String evidenceMediaType) {
        this.evidenceMediaType = evidenceMediaType;
    }

    public Long getEvidenceSize() {
        return evidenceSize;
    }

    public void setEvidenceSize(Long evidenceSize) {
        this.evidenceSize = evidenceSize;
    }

    public LocalDateTime getEvidenceUploadedAt() {
        return evidenceUploadedAt;
    }

    public void setEvidenceUploadedAt(LocalDateTime evidenceUploadedAt) {
        this.evidenceUploadedAt = evidenceUploadedAt;
    }

    public int getEvidenceVersion() {
        return evidenceVersion;
    }

    public void setEvidenceVersion(int evidenceVersion) {
        this.evidenceVersion = evidenceVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAiStatus() {
        return aiStatus;
    }

    public void setAiStatus(String aiStatus) {
        this.aiStatus = aiStatus;
    }

    public String getAiFindings() {
        return aiFindings;
    }

    public void setAiFindings(String aiFindings) {
        this.aiFindings = aiFindings;
    }

    public String getAnalysisMethod() {
        return analysisMethod;
    }

    public void setAnalysisMethod(String analysisMethod) {
        this.analysisMethod = analysisMethod;
    }

    public String getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(String ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public String getDecisionExplanation() {
        return decisionExplanation;
    }

    public void setDecisionExplanation(String decisionExplanation) {
        this.decisionExplanation = decisionExplanation;
    }

    public String getRequestedEvidence() {
        return requestedEvidence;
    }

    public void setRequestedEvidence(String requestedEvidence) {
        this.requestedEvidence = requestedEvidence;
    }

    public LocalDateTime getDecisionAt() {
        return decisionAt;
    }

    public void setDecisionAt(LocalDateTime decisionAt) {
        this.decisionAt = decisionAt;
    }

    public String getCorroborationStatus() {
        return corroborationStatus;
    }

    public void setCorroborationStatus(String corroborationStatus) {
        this.corroborationStatus = corroborationStatus;
    }

    public String getAppealNote() {
        return appealNote;
    }

    public void setAppealNote(String appealNote) {
        this.appealNote = appealNote;
    }

    public String getReviewerEmail() {
        return reviewerEmail;
    }

    public void setReviewerEmail(String reviewerEmail) {
        this.reviewerEmail = reviewerEmail;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
