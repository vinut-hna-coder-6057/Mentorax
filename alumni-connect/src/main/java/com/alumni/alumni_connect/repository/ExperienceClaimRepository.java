package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExperienceClaimRepository extends JpaRepository<ExperienceClaim, Long> {
    List<ExperienceClaim> findByUserId(Long userId, Pageable pageable);

    Optional<ExperienceClaim> findByIdAndUserId(Long id, Long userId);

    List<ExperienceClaim> findByStatus(String status, Pageable pageable);

    List<ExperienceClaim> findByStatusIn(List<String> statuses, Pageable pageable);

    Optional<ExperienceClaim> findByUserIdAndRequestId(Long userId, String requestId);
}
