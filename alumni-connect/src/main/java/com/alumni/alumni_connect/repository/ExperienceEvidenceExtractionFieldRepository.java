package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionField;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceEvidenceExtractionFieldRepository
        extends JpaRepository<ExperienceEvidenceExtractionField, Long> {
    List<ExperienceEvidenceExtractionField> findByExtractionIdOrderByFieldOrdinalAscIdAsc(Long extractionId);
}
