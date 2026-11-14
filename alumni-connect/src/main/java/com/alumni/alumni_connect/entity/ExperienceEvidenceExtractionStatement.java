package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "experience_evidence_extraction_statement", uniqueConstraints =
        @UniqueConstraint(name = "uk_experience_extraction_statement_ordinal",
                columnNames = {"extraction_id", "statement_ordinal"}))
public class ExperienceEvidenceExtractionStatement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "extraction_id", nullable = false)
    private Long extractionId;

    @Column(name = "statement_ordinal", nullable = false)
    private Integer statementOrdinal;

    @Column(name = "statement_kind", nullable = false, length = 32)
    private String statementKind;

    @Column(name = "statement_text", nullable = false, length = 500)
    private String statementText;

    protected ExperienceEvidenceExtractionStatement() {}

    public ExperienceEvidenceExtractionStatement(
            Long extractionId, Integer statementOrdinal, String statementKind, String statementText) {
        this.extractionId = extractionId;
        this.statementOrdinal = statementOrdinal;
        this.statementKind = statementKind;
        this.statementText = statementText;
    }

    public Long getId() { return id; }
    public Long getExtractionId() { return extractionId; }
    public Integer getStatementOrdinal() { return statementOrdinal; }
    public String getStatementKind() { return statementKind; }
    public String getStatementText() { return statementText; }
}
