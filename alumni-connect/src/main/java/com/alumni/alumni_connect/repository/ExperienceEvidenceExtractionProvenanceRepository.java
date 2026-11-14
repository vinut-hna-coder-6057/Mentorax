package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionProvenance;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceEvidenceExtractionProvenanceRepository
        extends JpaRepository<ExperienceEvidenceExtractionProvenance, Long> {
    List<ExperienceEvidenceExtractionProvenance> findByExtractionIdOrderByProvenanceOrdinalAscIdAsc(
            Long extractionId);
}
