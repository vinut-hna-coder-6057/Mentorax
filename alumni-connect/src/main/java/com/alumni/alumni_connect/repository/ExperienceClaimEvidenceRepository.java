package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExperienceClaimEvidenceRepository extends JpaRepository<ExperienceClaimEvidence, Long> {
    List<ExperienceClaimEvidence> findByClaimIdOrderByEvidenceVersionAsc(Long claimId);

    Optional<ExperienceClaimEvidence> findByClaimIdAndEvidenceHash(Long claimId, String evidenceHash);

    void deleteByClaimId(Long claimId);
}
