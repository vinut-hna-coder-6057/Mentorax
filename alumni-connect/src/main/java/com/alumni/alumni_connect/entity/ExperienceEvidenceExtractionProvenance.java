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
@Table(name = "experience_evidence_extraction_provenance", uniqueConstraints =
        @UniqueConstraint(name = "uk_experience_extraction_provenance_ordinal",
                columnNames = {"extraction_id", "provenance_ordinal"}))
public class ExperienceEvidenceExtractionProvenance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_id", nullable = false)
    private Long extractionId;

    @Column(name = "record_type", nullable = false, length = 16)
    private String recordType;

    @Column(name = "field_id")
    private Long fieldId;

    @Column(name = "statement_id")
    private Long statementId;

    @Column(name = "provenance_ordinal", nullable = false)
    private Integer provenanceOrdinal;

    @Column(name = "source_evidence_id", nullable = false)
    private Long sourceEvidenceId;

    @Column(name = "source_evidence_version", nullable = false)
    private Integer sourceEvidenceVersion;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "text_excerpt", nullable = false, length = 500)
    private String textExcerpt;

    @Column(name = "extraction_method", nullable = false, length = 16)
    private String extractionMethod;

    @Column(name = "source_confidence", precision = 6, scale = 5)
    private BigDecimal sourceConfidence;

    @Column(name = "bounding_box_x", precision = 8, scale = 6)
    private BigDecimal boundingBoxX;

    @Column(name = "bounding_box_y", precision = 8, scale = 6)
    private BigDecimal boundingBoxY;

    @Column(name = "bounding_box_width", precision = 8, scale = 6)
    private BigDecimal boundingBoxWidth;

    @Column(name = "bounding_box_height", precision = 8, scale = 6)
    private BigDecimal boundingBoxHeight;

    protected ExperienceEvidenceExtractionProvenance() {}

    public ExperienceEvidenceExtractionProvenance(
            Long extractionId,
            String recordType,
            Long fieldId,
            Long statementId,
            Integer provenanceOrdinal,
            Long sourceEvidenceId,
            Integer sourceEvidenceVersion,
            Integer pageNumber,
            String textExcerpt,
            String extractionMethod,
            BigDecimal sourceConfidence,
            BigDecimal boundingBoxX,
            BigDecimal boundingBoxY,
            BigDecimal boundingBoxWidth,
            BigDecimal boundingBoxHeight) {
        this.extractionId = extractionId;
        this.recordType = recordType;
        this.fieldId = fieldId;
        this.statementId = statementId;
        this.provenanceOrdinal = provenanceOrdinal;
        this.sourceEvidenceId = sourceEvidenceId;
        this.sourceEvidenceVersion = sourceEvidenceVersion;
        this.pageNumber = pageNumber;
        this.textExcerpt = textExcerpt;
        this.extractionMethod = extractionMethod;
        this.sourceConfidence = sourceConfidence;
        this.boundingBoxX = boundingBoxX;
        this.boundingBoxY = boundingBoxY;
        this.boundingBoxWidth = boundingBoxWidth;
        this.boundingBoxHeight = boundingBoxHeight;
    }

    public Long getId() { return id; }
    public Long getExtractionId() { return extractionId; }
    public String getRecordType() { return recordType; }
    public Long getFieldId() { return fieldId; }
    public Long getStatementId() { return statementId; }
    public Integer getProvenanceOrdinal() { return provenanceOrdinal; }
    public Long getSourceEvidenceId() { return sourceEvidenceId; }
    public Integer getSourceEvidenceVersion() { return sourceEvidenceVersion; }
    public Integer getPageNumber() { return pageNumber; }
    public String getTextExcerpt() { return textExcerpt; }
    public String getExtractionMethod() { return extractionMethod; }
    public BigDecimal getSourceConfidence() { return sourceConfidence; }
    public BigDecimal getBoundingBoxX() { return boundingBoxX; }
    public BigDecimal getBoundingBoxY() { return boundingBoxY; }
    public BigDecimal getBoundingBoxWidth() { return boundingBoxWidth; }
    public BigDecimal getBoundingBoxHeight() { return boundingBoxHeight; }
}
