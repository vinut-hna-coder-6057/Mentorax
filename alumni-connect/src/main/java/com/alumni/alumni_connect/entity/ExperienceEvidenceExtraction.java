package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "experience_evidence_extraction", uniqueConstraints = {
        @UniqueConstraint(name = "uk_experience_extraction_response",
                columnNames = {"evidence_id", "evidence_version", "response_hash"}),
        @UniqueConstraint(name = "uk_experience_extraction_id_evidence",
                columnNames = {"id", "evidence_id", "evidence_version"})
})
public class ExperienceEvidenceExtraction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evidence_id", nullable = false)
    private Long evidenceId;

    @Column(name = "evidence_version", nullable = false)
    private Integer evidenceVersion;

    @Column(name = "schema_version", nullable = false, length = 64)
    private String schemaVersion;

    @Column(name = "document_sha256", nullable = false, length = 64)
    private String documentSha256;

    @Column(name = "response_hash", nullable = false, length = 64)
    private String responseHash;

    @Column(name = "analysis_status", nullable = false, length = 32)
    private String analysisStatus;

    @Column(name = "document_classification", nullable = false, length = 50)
    private String documentClassification;

    @Column(name = "classification_confidence", nullable = false, precision = 6, scale = 5)
    private BigDecimal classificationConfidence;

    @Column(name = "ocr_status", nullable = false, length = 32)
    private String ocrStatus;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected ExperienceEvidenceExtraction() {}

    public ExperienceEvidenceExtraction(
            Long evidenceId,
            Integer evidenceVersion,
            String schemaVersion,
            String documentSha256,
            String responseHash,
            String analysisStatus,
            String documentClassification,
            BigDecimal classificationConfidence,
            String ocrStatus,
            LocalDateTime processedAt) {
        this.evidenceId = evidenceId;
        this.evidenceVersion = evidenceVersion;
        this.schemaVersion = schemaVersion;
        this.documentSha256 = documentSha256;
        this.responseHash = responseHash;
        this.analysisStatus = analysisStatus;
        this.documentClassification = documentClassification;
        this.classificationConfidence = classificationConfidence;
        this.ocrStatus = ocrStatus;
        this.processedAt = processedAt;
    }

    public Long getId() { return id; }
    public Long getEvidenceId() { return evidenceId; }
    public Integer getEvidenceVersion() { return evidenceVersion; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getDocumentSha256() { return documentSha256; }
    public String getResponseHash() { return responseHash; }
    public String getAnalysisStatus() { return analysisStatus; }
    public String getDocumentClassification() { return documentClassification; }
    public BigDecimal getClassificationConfidence() { return classificationConfidence; }
    public String getOcrStatus() { return ocrStatus; }
    public LocalDateTime getProcessedAt() { return processedAt; }
}
