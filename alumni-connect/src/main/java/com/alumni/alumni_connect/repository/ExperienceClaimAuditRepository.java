package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceClaimAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExperienceClaimAuditRepository extends JpaRepository<ExperienceClaimAudit, Long> {
    List<ExperienceClaimAudit> findByClaimIdOrderByCreatedAtAsc(Long claimId);
}
