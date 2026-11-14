package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;

@Entity
@Table(name = "experience_evidence_extraction_field", uniqueConstraints = {
        @UniqueConstraint(name = "uk_experience_extraction_field_ordinal",
                columnNames = {"extraction_id", "field_ordinal"}),
        @UniqueConstraint(name = "uk_experience_extraction_field_id_run",
                columnNames = {"id", "extraction_id"})
})
public class ExperienceEvidenceExtractionField {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_id", nullable = false)
    private Long extractionId;

    @Column(name = "field_ordinal", nullable = false)
    private Integer fieldOrdinal;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "field_status", nullable = false, length = 32)
    private String fieldStatus;

    @Column(name = "extracted_value", length = 500)
    private String extractedValue;

    @Column(precision = 6, scale = 5)
    private BigDecimal confidence;

    @Column(name = "model_confidence", precision = 6, scale = 5)
    private BigDecimal modelConfidence;

    @Column(name = "extraction_method", nullable = false, length = 32)
    private String extractionMethod;

    protected ExperienceEvidenceExtractionField() {}

    public ExperienceEvidenceExtractionField(
            Long extractionId,
            Integer fieldOrdinal,
            String fieldName,
            String fieldStatus,
            String extractedValue,
            BigDecimal confidence,
            BigDecimal modelConfidence,
            String extractionMethod) {
        this.extractionId = extractionId;
        this.fieldOrdinal = fieldOrdinal;
        this.fieldName = fieldName;
        this.fieldStatus = fieldStatus;
        this.extractedValue = extractedValue;
        this.confidence = confidence;
        this.modelConfidence = modelConfidence;
        this.extractionMethod = extractionMethod;
    }

    public Long getId() { return id; }
    public Long getExtractionId() { return extractionId; }
    public Integer getFieldOrdinal() { return fieldOrdinal; }
    public String getFieldName() { return fieldName; }
    public String getFieldStatus() { return fieldStatus; }
    public String getExtractedValue() { return extractedValue; }
    public BigDecimal getConfidence() { return confidence; }
    public BigDecimal getModelConfidence() { return modelConfidence; }
    public String getExtractionMethod() { return extractionMethod; }
}
