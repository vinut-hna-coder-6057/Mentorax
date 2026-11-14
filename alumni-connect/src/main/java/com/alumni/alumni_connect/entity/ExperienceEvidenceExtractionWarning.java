package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "experience_evidence_extraction_warning", uniqueConstraints =
        @UniqueConstraint(name = "uk_experience_extraction_warning_ordinal",
                columnNames = {"extraction_id", "warning_ordinal"}))
public class ExperienceEvidenceExtractionWarning {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_id", nullable = false)
    private Long extractionId;

    @Column(name = "warning_ordinal", nullable = false)
    private Integer warningOrdinal;

    @Column(name = "warning_text", nullable = false, length = 500)
    private String warningText;

    protected ExperienceEvidenceExtractionWarning() {}

    public ExperienceEvidenceExtractionWarning(Long extractionId, Integer warningOrdinal, String warningText) {
        this.extractionId = extractionId;
        this.warningOrdinal = warningOrdinal;
        this.warningText = warningText;
    }

    public Long getId() { return id; }
    public Long getExtractionId() { return extractionId; }
    public Integer getWarningOrdinal() { return warningOrdinal; }
    public String getWarningText() { return warningText; }
}
