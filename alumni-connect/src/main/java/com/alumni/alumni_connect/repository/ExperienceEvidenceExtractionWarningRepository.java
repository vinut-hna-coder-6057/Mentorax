package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionWarning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceEvidenceExtractionWarningRepository
        extends JpaRepository<ExperienceEvidenceExtractionWarning, Long> {
    List<ExperienceEvidenceExtractionWarning> findByExtractionIdOrderByWarningOrdinalAscIdAsc(
            Long extractionId);
}
