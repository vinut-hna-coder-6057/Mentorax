package com.alumni.alumni_connect.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "experience_evidence_extraction_candidate", uniqueConstraints =
        @UniqueConstraint(name = "uk_experience_extraction_candidate_ordinal",
                columnNames = {"field_id", "candidate_ordinal"}))
public class ExperienceEvidenceExtractionCandidate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    @Column(name = "candidate_ordinal", nullable = false)
    private Integer candidateOrdinal;

    @Column(name = "candidate_value", nullable = false, length = 500)
    private String candidateValue;

    protected ExperienceEvidenceExtractionCandidate() {}

    public ExperienceEvidenceExtractionCandidate(Long fieldId, Integer candidateOrdinal, String candidateValue) {
        this.fieldId = fieldId;
        this.candidateOrdinal = candidateOrdinal;
        this.candidateValue = candidateValue;
    }

    public Long getId() { return id; }
    public Long getFieldId() { return fieldId; }
    public Integer getCandidateOrdinal() { return candidateOrdinal; }
    public String getCandidateValue() { return candidateValue; }
}
