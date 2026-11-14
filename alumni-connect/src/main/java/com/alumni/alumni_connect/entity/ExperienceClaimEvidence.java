package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "experience_claim_evidence")
public class ExperienceClaimEvidence {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "claim_id", nullable = false)
    private Long claimId;

    @Column(name = "evidence_version", nullable = false)
    private int evidenceVersion;

    @Column(name = "evidence_storage_key", nullable = false, length = 64)
    private String evidenceStorageKey;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "encrypted_evidence", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] encryptedEvidence;

    @Column(name = "evidence_file_name", nullable = false, length = 255)
    private String evidenceFileName;

    @Column(name = "evidence_media_type", nullable = false, length = 100)
    private String evidenceMediaType;

    @Column(name = "evidence_size", nullable = false)
    private Long evidenceSize;

    @Column(name = "evidence_hash", nullable = false, length = 128)
    private String evidenceHash;

    @Column(name = "analysis_status", nullable = false, length = 32)
    private String analysisStatus = "QUEUED";

    @Column(name = "analysis_findings", columnDefinition = "TEXT")
    private String analysisFindings;

    @Column(name = "evidence_status", nullable = false, length = 32)
    private String evidenceStatus = "ACTIVE";

    @Column(name = "relationship_type", length = 32)
    private String relationshipType;

    @Column(name = "related_evidence_id")
    private Long relatedEvidenceId;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ExperienceClaimEvidence() {}

    public ExperienceClaimEvidence(Long claimId, int evidenceVersion, String evidenceStorageKey,
                                   byte[] encryptedEvidence, String evidenceFileName,
                                   String evidenceMediaType, Long evidenceSize, String evidenceHash) {
        this.claimId = claimId;
        this.evidenceVersion = evidenceVersion;
        this.evidenceStorageKey = evidenceStorageKey;
        this.encryptedEvidence = encryptedEvidence;
        this.evidenceFileName = evidenceFileName;
        this.evidenceMediaType = evidenceMediaType;
        this.evidenceSize = evidenceSize;
        this.evidenceHash = evidenceHash;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getClaimId() { return claimId; }
    public int getEvidenceVersion() { return evidenceVersion; }
    public String getEvidenceStorageKey() { return evidenceStorageKey; }
    public byte[] getEncryptedEvidence() { return encryptedEvidence; }
    public String getEvidenceFileName() { return evidenceFileName; }
    public String getEvidenceMediaType() { return evidenceMediaType; }
    public Long getEvidenceSize() { return evidenceSize; }
    public String getEvidenceHash() { return evidenceHash; }
    public String getAnalysisStatus() { return analysisStatus; }
    public void setAnalysisStatus(String analysisStatus) { this.analysisStatus = analysisStatus; }
    public String getAnalysisFindings() { return analysisFindings; }
    public void setAnalysisFindings(String analysisFindings) { this.analysisFindings = analysisFindings; }
    public String getEvidenceStatus() { return evidenceStatus; }
    public void setEvidenceStatus(String evidenceStatus) { this.evidenceStatus = evidenceStatus; }
    public String getRelationshipType() { return relationshipType; }
    public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }
    public Long getRelatedEvidenceId() { return relatedEvidenceId; }
    public void setRelatedEvidenceId(Long relatedEvidenceId) { this.relatedEvidenceId = relatedEvidenceId; }
    public LocalDateTime getSupersededAt() { return supersededAt; }
    public void setSupersededAt(LocalDateTime supersededAt) { this.supersededAt = supersededAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
