package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceEvidenceExtraction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExperienceEvidenceExtractionRepository
        extends JpaRepository<ExperienceEvidenceExtraction, Long> {
    Optional<ExperienceEvidenceExtraction> findByEvidenceIdAndEvidenceVersionAndResponseHash(
            Long evidenceId, Integer evidenceVersion, String responseHash);

    Optional<ExperienceEvidenceExtraction> findFirstByEvidenceIdAndEvidenceVersionOrderByProcessedAtDescIdDesc(
            Long evidenceId, Integer evidenceVersion);
}
