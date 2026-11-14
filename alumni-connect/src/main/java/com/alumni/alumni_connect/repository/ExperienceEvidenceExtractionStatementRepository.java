package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceEvidenceExtractionStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceEvidenceExtractionStatementRepository
        extends JpaRepository<ExperienceEvidenceExtractionStatement, Long> {
    List<ExperienceEvidenceExtractionStatement> findByExtractionIdOrderByStatementOrdinalAscIdAsc(
            Long extractionId);
}
