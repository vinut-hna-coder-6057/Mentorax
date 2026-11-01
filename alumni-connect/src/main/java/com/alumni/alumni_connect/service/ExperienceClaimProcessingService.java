package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ExperienceEvidenceAnalysisRequest;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimAudit;
import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import com.alumni.alumni_connect.entity.ExperienceClaimProcessing;
import com.alumni.alumni_connect.repository.ExperienceClaimAuditRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimEvidenceRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimProcessingRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.alumni.alumni_connect.service.ExperienceEvidenceAnalysisService.AnalysisResult;
import com.alumni.alumni_connect.service.NotificationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ExperienceClaimProcessingService {
    private static final Logger log = LoggerFactory.getLogger(ExperienceClaimProcessingService.class);
    private static final int MAX_EVIDENCE_VERSIONS = 10;

    private final ExperienceClaimRepository claimRepository;
    private final ExperienceClaimEvidenceRepository evidenceRepository;
    private final ExperienceClaimProcessingRepository processingRepository;
    private final ExperienceClaimAuditRepository auditRepository;
    private final ExperienceEvidenceCrypto evidenceCrypto;
    private final ExperienceEvidenceAnalysisService analysisService;
    private final ExperienceVerificationDecisionEngine decisionEngine;
    private final EmploymentCorroborationService corroborationService;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final int maxAttempts;

    public ExperienceClaimProcessingService(
            ExperienceClaimRepository claimRepository,
            ExperienceClaimEvidenceRepository evidenceRepository,
            ExperienceClaimProcessingRepository processingRepository,
            ExperienceClaimAuditRepository auditRepository,
            ExperienceEvidenceCrypto evidenceCrypto,
            ExperienceEvidenceAnalysisService analysisService,
            ExperienceVerificationDecisionEngine decisionEngine,
            EmploymentCorroborationService corroborationService,
            NotificationService notificationService,
            ObjectMapper objectMapper,
            @Value("${app.experience-verification.max-attempts:5}") int maxAttempts) {
        this.claimRepository = claimRepository;
        this.evidenceRepository = evidenceRepository;
        this.processingRepository = processingRepository;
        this.auditRepository = auditRepository;
        this.evidenceCrypto = evidenceCrypto;
        this.analysisService = analysisService;
        this.decisionEngine = decisionEngine;
        this.corroborationService = corroborationService;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Transactional
    public void processClaim(Long claimId) {
        ExperienceClaim claim = claimRepository.findByIdForUpdate(claimId).orElse(null);
        ExperienceClaimProcessing job = processingRepository.findById(claimId).orElse(null);
        if (claim == null || job == null) return;

        int processingVersion = job.getEvidenceVersion();
        if (claim.getEvidenceVersion() != processingVersion) return;

        String oldStatus = claim.getStatus();
        claim.setStatus("PROCESSING");
        claim.setAiStatus("PROCESSING");
        claim.setDecisionExplanation("Your documents are being checked for consistency with the claim.");
        claim.setRequestedEvidence(null);
        claim.setDecisionAt(null);
        claimRepository.save(claim);

        try {
            List<ExperienceClaimEvidence> evidenceVersions =
                    evidenceRepository.findByClaimIdOrderByEvidenceVersionAsc(claimId);
            List<AnalysisResult> analyses = new ArrayList<>();
            List<Integer> analysisEvidenceVersions = new ArrayList<>();
            ArrayNode combinedFindings = objectMapper.createArrayNode();
            for (ExperienceClaimEvidence evidence : evidenceVersions) {
                byte[] document = evidenceCrypto.decrypt(evidence.getEncryptedEvidence());
                AnalysisResult result = analysisService.analyze(
                        analysisRequest(claim),
                        document,
                        evidence.getEvidenceFileName(),
                        evidence.getEvidenceMediaType());
                if ("UNAVAILABLE".equals(result.status())) {
                    throw new RetryableAnalysisException();
                }
                evidence.setAnalysisStatus(result.status());
                evidence.setAnalysisFindings(result.findings());
                evidenceRepository.save(evidence);
                analyses.add(result);
                analysisEvidenceVersions.add(evidence.getEvidenceVersion());
                appendFindings(combinedFindings, result, evidence.getEvidenceVersion());
            }

            if (analyses.isEmpty() && claim.getEncryptedEvidence() != null) {
                AnalysisResult result = analysisService.analyze(
                        analysisRequest(claim),
                        evidenceCrypto.decrypt(claim.getEncryptedEvidence()),
                        claim.getEvidenceFileName(),
                        claim.getEvidenceMediaType());
                if ("UNAVAILABLE".equals(result.status())) {
                    throw new RetryableAnalysisException();
                }
                analyses.add(result);
                analysisEvidenceVersions.add(claim.getEvidenceVersion());
                appendFindings(combinedFindings, result, claim.getEvidenceVersion());
            }
            appendCrossDocumentFindings(combinedFindings);
            appendDuplicateDocumentFindings(combinedFindings, analyses, analysisEvidenceVersions);

            List<AnalysisResult> decisionAnalyses = new ArrayList<>(analyses);
            ArrayNode crossDocumentFindings = crossDocumentFindings(combinedFindings);
            if (!crossDocumentFindings.isEmpty()) {
                decisionAnalyses.add(new AnalysisResult(
                        "ANALYZED",
                        objectMapper.writeValueAsString(crossDocumentFindings),
                        "UNKNOWN",
                        0,
                        "HEURISTIC_FALLBACK",
                        "NOT_NEEDED"));
            }
            ExperienceVerificationDecisionEngine.Decision decision =
                    decisionEngine.decideEvidenceBased(claim, decisionAnalyses);
            EmploymentCorroborationService.Result corroboration =
                    "VERIFIED_FROM_EVIDENCE".equals(decision.status())
                            ? corroborationService.verify(claim)
                            : new EmploymentCorroborationService.Result("NOT_CHECKED", false);
            if (corroboration.independentlyAuthenticated()) {
                decision = new ExperienceVerificationDecisionEngine.Decision(
                        "EMPLOYER_CONFIRMED",
                        "Submitted evidence is consistent and an independent employer verification provider confirmed the claim.",
                        List.of());
            }
            claim.setAiStatus(analyses.stream().anyMatch(a -> "INCONCLUSIVE".equals(a.status()))
                    ? "INCONCLUSIVE" : "ANALYZED");
            claim.setAiFindings(objectMapper.writeValueAsString(combinedFindings));
            claim.setAnalysisMethod(aggregateAnalysisMethod(analyses));
            claim.setOcrStatus(aggregateOcrStatus(analyses));
            claim.setStatus(decision.status());
            claim.setDecisionExplanation(decision.explanation());
            claim.setRequestedEvidence(objectMapper.writeValueAsString(decision.requestedEvidence()));
            claim.setDecisionAt(LocalDateTime.now());
            claim.setCorroborationStatus(corroboration.status());
            claimRepository.save(claim);
            auditRepository.save(new ExperienceClaimAudit(
                    claimId,
                    "verification-engine",
                    "AUTOMATED_DECISION",
                    oldStatus,
                    decision.status(),
                    boundedNote(decision.explanation())));
            processingRepository.deleteByClaimIdAndEvidenceVersion(claimId, processingVersion);
            notificationService.sendNotification(
                    claim.getUser().getEmail(),
                    "Experience claim analysis is complete: " + decision.status().replace('_', ' '),
                    "EXPERIENCE_CLAIM_UPDATED",
                    "/profile");
        } catch (RetryableAnalysisException exception) {
            scheduleRetry(claim, job, oldStatus, "The analysis service is temporarily unavailable.");
        } catch (JsonProcessingException exception) {
            log.error("Could not serialize validated findings for claim {}", claimId, exception);
            scheduleRetry(claim, job, oldStatus, "Validated findings could not be saved.");
        } catch (IllegalStateException exception) {
            log.error("Could not process encrypted evidence for claim {}", claimId, exception);
            markFailed(claim, job, oldStatus, "Private evidence could not be processed safely.");
        }
    }

    @Transactional
    public void recordUnexpectedFailure(Long claimId) {
        ExperienceClaim claim = claimRepository.findByIdForUpdate(claimId).orElse(null);
        ExperienceClaimProcessing job = processingRepository.findById(claimId).orElse(null);
        if (claim == null || job == null) return;
        scheduleRetry(claim, job, claim.getStatus(),
                "An unexpected processing error occurred.");
    }

    private ExperienceEvidenceAnalysisRequest analysisRequest(ExperienceClaim claim) {
        return new ExperienceEvidenceAnalysisRequest(
                claim.getCompany(),
                claim.getJobTitle(),
                claim.getStartMonth(),
                claim.getEndMonth(),
                claim.getSummary(),
                claim.getUser() == null ? null : claim.getUser().getName());
    }

    private void appendFindings(ArrayNode target, AnalysisResult result, int evidenceVersion) {
        try {
            JsonNode parsed = objectMapper.readTree(result.findings());
            if (parsed == null || !parsed.isArray()) {
                throw new IllegalArgumentException("Analysis findings must be an array");
            }
            for (JsonNode finding : parsed) {
                JsonNode copy = finding.deepCopy();
                if (copy instanceof com.fasterxml.jackson.databind.node.ObjectNode item) {
                    item.put("evidenceVersion", evidenceVersion);
                    item.put("documentType", result.documentType());
                    target.add(item);
                }
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Analysis findings are malformed", exception);
        }
    }

    private void appendCrossDocumentFindings(ArrayNode findings) {
        Map<String, Map<String, Set<Integer>>> valuesByField = new LinkedHashMap<>();
        for (JsonNode finding : findings) {
            String field = finding.path("field").asText();
            JsonNode extracted = finding.path("extractedValue");
            int evidenceVersion = finding.path("evidenceVersion").asInt();
            if (!Set.of("employeeName", "company", "jobTitle", "startMonth", "endMonth",
                    "employmentType", "issueDate", "employmentStatements").contains(field)
                    || !extracted.isTextual() || extracted.asText().isBlank() || evidenceVersion <= 0) {
                continue;
            }
            valuesByField.computeIfAbsent(field, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(extracted.asText().trim().toLowerCase(), ignored -> new LinkedHashSet<>())
                    .add(evidenceVersion);
        }
        valuesByField.forEach((field, values) -> {
            Set<Integer> versions = new LinkedHashSet<>();
            values.values().forEach(versions::addAll);
            if (values.size() < 2 || versions.size() < 2) return;
            var conflict = objectMapper.createObjectNode();
            conflict.put("field", field);
            conflict.put("outcome", Set.of(
                    "employeeName", "company", "jobTitle", "startMonth", "endMonth", "employmentType")
                    .contains(field) ? "MISMATCH" : "INCONCLUSIVE");
            conflict.put("explanation",
                    "The extracted " + field + " differs between submitted evidence documents.");
            var evidenceReferences = conflict.putArray("evidenceReferences");
            for (Integer version : versions) evidenceReferences.add("document-version-" + version);
            var versionReferences = conflict.putArray("evidenceVersions");
            versions.forEach(versionReferences::add);
            findings.add(conflict);
        });
    }

    private void appendDuplicateDocumentFindings(
            ArrayNode findings, List<AnalysisResult> analyses, List<Integer> evidenceVersions) {
        Map<String, Set<Integer>> versionsByFingerprint = new LinkedHashMap<>();
        for (int index = 0; index < analyses.size(); index++) {
            String fingerprint = analyses.get(index).extractedTextFingerprint();
            if (fingerprint == null || fingerprint.isBlank()) continue;
            versionsByFingerprint.computeIfAbsent(fingerprint, ignored -> new LinkedHashSet<>())
                    .add(evidenceVersions.get(index));
        }
        versionsByFingerprint.values().stream()
                .filter(versions -> versions.size() > 1)
                .forEach(versions -> {
                    var duplicate = objectMapper.createObjectNode();
                    duplicate.put("field", "duplicateDocument");
                    duplicate.put("outcome", "INCONCLUSIVE");
                    duplicate.put("explanation",
                            "The extracted text is identical in multiple submitted documents.");
                    var evidenceReferences = duplicate.putArray("evidenceReferences");
                    var versionReferences = duplicate.putArray("evidenceVersions");
                    versions.forEach(version -> {
                        evidenceReferences.add("document-version-" + version);
                        versionReferences.add(version);
                    });
                    findings.add(duplicate);
                });
    }

    private ArrayNode crossDocumentFindings(ArrayNode findings) {
        ArrayNode crossDocument = objectMapper.createArrayNode();
        for (JsonNode finding : findings) {
            if (finding.has("evidenceVersions")) {
                crossDocument.add(finding.deepCopy());
            }
        }
        return crossDocument;
    }

    private void scheduleRetry(ExperienceClaim claim, ExperienceClaimProcessing job,
                               String oldStatus, String safeReason) {
        if (job.getAttemptCount() >= maxAttempts) {
            markFailed(claim, job, oldStatus, safeReason);
            return;
        }
        long delaySeconds = Math.min(300L, 5L << Math.min(job.getAttemptCount() - 1, 5));
        LocalDateTime now = LocalDateTime.now();
        job.setNextAttemptAt(now.plusSeconds(delaySeconds));
        job.setLeaseUntil(null);
        job.setLastError(safeReason);
        job.setUpdatedAt(now);
        processingRepository.save(job);
        claim.setStatus("PROCESSING");
        claim.setAiStatus("PROCESSING");
        claim.setDecisionExplanation(
                "Evidence analysis is temporarily unavailable. The system will retry automatically.");
        claim.setRequestedEvidence(null);
        claim.setCorroborationStatus("NOT_CHECKED");
        claimRepository.save(claim);
        log.warn("Experience analysis retry scheduled for claim {} after attempt {}",
                claim.getId(), job.getAttemptCount());
    }

    private void markFailed(ExperienceClaim claim, ExperienceClaimProcessing job,
                            String oldStatus, String safeReason) {
        claim.setStatus("PROCESSING_FAILED");
        claim.setAiStatus("UNAVAILABLE");
        claim.setAnalysisMethod("UNAVAILABLE");
        claim.setOcrStatus("FAILED");
        claim.setDecisionExplanation(
                "The evidence service could not complete its analysis. No verification decision was made. "
                        + "Retry the analysis or upload evidence again.");
        claim.setRequestedEvidence(null);
        claim.setDecisionAt(null);
        claim.setCorroborationStatus("NOT_CHECKED");
        claimRepository.save(claim);
        auditRepository.save(new ExperienceClaimAudit(
                claim.getId(), "verification-engine", "PROCESSING_FAILED",
                oldStatus, "PROCESSING_FAILED", boundedNote(safeReason)));
        processingRepository.delete(job);
        notificationService.sendNotification(
                claim.getUser().getEmail(),
                "Experience claim processing failed. Retry analysis or upload clearer evidence.",
                "EXPERIENCE_CLAIM_UPDATED",
                "/profile");
        log.error("Experience analysis stopped safely for claim {} after {} attempts: {}",
                claim.getId(), job.getAttemptCount(), safeReason);
    }

    private static String boundedNote(String note) {
        return note.length() <= 500 ? note : note.substring(0, 497) + "...";
    }

    private static String aggregateAnalysisMethod(List<AnalysisResult> analyses) {
        boolean usedOcr = analyses.stream().anyMatch(analysis -> !"NOT_NEEDED".equals(analysis.ocrStatus()));
        boolean usedLlm = analyses.stream().anyMatch(analysis ->
                "LLM_ANALYSIS".equals(analysis.analysisMethod())
                        || "OCR_PLUS_LLM".equals(analysis.analysisMethod()));
        if (usedOcr) return usedLlm ? "OCR_PLUS_LLM" : "OCR_PLUS_HEURISTIC";
        return usedLlm ? "LLM_ANALYSIS" : "HEURISTIC_FALLBACK";
    }

    private static String aggregateOcrStatus(List<AnalysisResult> analyses) {
        if (analyses.stream().anyMatch(analysis -> "FAILED".equals(analysis.ocrStatus()))) return "FAILED";
        if (analyses.stream().anyMatch(analysis -> "SUCCESS".equals(analysis.ocrStatus()))) return "SUCCESS";
        return "NOT_NEEDED";
    }

    private static final class RetryableAnalysisException extends RuntimeException {}
}
