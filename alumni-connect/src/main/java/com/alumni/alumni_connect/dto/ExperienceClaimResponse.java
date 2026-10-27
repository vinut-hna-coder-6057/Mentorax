package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.ExperienceClaim;

import java.time.LocalDateTime;

public record ExperienceClaimResponse(
        Long id,
        Long userId,
        String company,
        String jobTitle,
        String requestId,
        String startMonth,
        String endMonth,
        String summary,
        String evidenceSummary,
        String evidenceHash,
        String evidenceFileName,
        String evidenceMediaType,
        Long evidenceSize,
        LocalDateTime evidenceUploadedAt,
        boolean evidenceAvailable,
        String status,
        String aiStatus,
        String aiFindings,
        String appealNote,
        String reviewerEmail,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ExperienceClaimResponse from(ExperienceClaim claim) {
        return new ExperienceClaimResponse(
                claim.getId(),
                claim.getUser() != null ? claim.getUser().getId() : null,
                claim.getCompany(),
                claim.getJobTitle(),
                claim.getRequestId(),
                claim.getStartMonth(),
                claim.getEndMonth(),
                claim.getSummary(),
                claim.getEvidenceSummary(),
                claim.getEvidenceHash(),
                claim.getEvidenceFileName(),
                claim.getEvidenceMediaType(),
                claim.getEvidenceSize(),
                claim.getEvidenceUploadedAt(),
                claim.getEncryptedEvidence() != null,
                claim.getStatus(),
                claim.getAiStatus(),
                claim.getAiFindings(),
                claim.getAppealNote(),
                claim.getReviewerEmail(),
                claim.getCreatedAt(),
                claim.getUpdatedAt()
        );
    }
}
