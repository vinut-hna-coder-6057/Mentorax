package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;

import java.time.LocalDateTime;

public record ExperienceClaimEvidenceResponse(
        Long id,
        int evidenceVersion,
        String evidenceFileName,
        String evidenceMediaType,
        Long evidenceSize,
        String evidenceHash,
        String analysisStatus,
        String analysisFindings,
        String evidenceStatus,
        String relationshipType,
        Long relatedEvidenceId,
        LocalDateTime createdAt,
        LocalDateTime supersededAt
) {
    public static ExperienceClaimEvidenceResponse from(ExperienceClaimEvidence evidence) {
        return new ExperienceClaimEvidenceResponse(
                evidence.getId(),
                evidence.getEvidenceVersion(),
                evidence.getEvidenceFileName(),
                evidence.getEvidenceMediaType(),
                evidence.getEvidenceSize(),
                evidence.getEvidenceHash(),
                evidence.getAnalysisStatus(),
                evidence.getAnalysisFindings(),
                evidence.getEvidenceStatus(),
                evidence.getRelationshipType(),
                evidence.getRelatedEvidenceId(),
                evidence.getCreatedAt(),
                evidence.getSupersededAt());
    }
}
