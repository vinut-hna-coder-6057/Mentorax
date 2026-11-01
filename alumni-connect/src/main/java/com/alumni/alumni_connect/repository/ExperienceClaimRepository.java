package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ExperienceClaimRepository extends JpaRepository<ExperienceClaim, Long> {
    List<ExperienceClaim> findByUserId(Long userId, Pageable pageable);

    Optional<ExperienceClaim> findByIdAndUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select claim from ExperienceClaim claim where claim.id = :id")
    Optional<ExperienceClaim> findByIdForUpdate(@Param("id") Long id);

    List<ExperienceClaim> findByStatus(String status, Pageable pageable);

    List<ExperienceClaim> findByStatusIn(List<String> statuses, Pageable pageable);

    Optional<ExperienceClaim> findByUserIdAndRequestId(Long userId, String requestId);
}
