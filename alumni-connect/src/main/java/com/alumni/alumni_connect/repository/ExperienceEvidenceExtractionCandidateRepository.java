package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceEvidenceExtractionCandidateRepository
        extends JpaRepository<ExperienceEvidenceExtractionCandidate, Long> {
    List<ExperienceEvidenceExtractionCandidate> findByFieldIdOrderByCandidateOrdinalAscIdAsc(Long fieldId);
}
