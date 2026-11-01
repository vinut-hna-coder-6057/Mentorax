package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceClaimProcessing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ExperienceClaimProcessingRepository extends JpaRepository<ExperienceClaimProcessing, Long> {
    @Query("""
            select job.claimId
            from ExperienceClaimProcessing job
            where job.nextAttemptAt <= :now
              and (job.leaseUntil is null or job.leaseUntil <= :now)
            order by job.nextAttemptAt
            """)
    List<Long> findDueClaimIds(@Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            update ExperienceClaimProcessing job
            set job.attemptCount = job.attemptCount + 1,
                job.leaseUntil = :leaseUntil,
                job.updatedAt = :now
            where job.claimId = :claimId
              and job.nextAttemptAt <= :now
              and (job.leaseUntil is null or job.leaseUntil <= :now)
            """)
    int acquire(@Param("claimId") Long claimId,
                @Param("now") LocalDateTime now,
                @Param("leaseUntil") LocalDateTime leaseUntil);

    void deleteByClaimId(Long claimId);

    void deleteByClaimIdAndEvidenceVersion(Long claimId, int evidenceVersion);
}
