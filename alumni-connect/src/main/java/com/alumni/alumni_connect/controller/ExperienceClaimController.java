package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimAudit;
import com.alumni.alumni_connect.entity.ExperienceClaimEvidence;
import com.alumni.alumni_connect.entity.ExperienceClaimProcessing;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ExperienceClaimAuditRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimEvidenceRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimProcessingRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.alumni.alumni_connect.service.ExperienceEvidenceCrypto;
import com.alumni.alumni_connect.service.ExperienceEvidencePdfValidator;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.*;

@RestController
@Validated
@RequestMapping("/experience-claims")
public class ExperienceClaimController {
    private static final int MAX_EVIDENCE_BYTES = 5 * 1024 * 1024;
    private static final int MAX_EVIDENCE_VERSIONS = 10;
    private final ExperienceClaimRepository repository;
    private final ExperienceClaimAuditRepository auditRepository;
    private final ExperienceClaimEvidenceRepository evidenceRepository;
    private final ExperienceClaimProcessingRepository processingRepository;
    private final CurrentUserService currentUserService;
    private final ExperienceEvidenceCrypto evidenceCrypto;
    private final ExperienceEvidencePdfValidator evidencePdfValidator;

    public ExperienceClaimController(ExperienceClaimRepository repository,
                                     ExperienceClaimAuditRepository auditRepository,
                                     ExperienceClaimEvidenceRepository evidenceRepository,
                                     ExperienceClaimProcessingRepository processingRepository,
                                     CurrentUserService currentUserService,
                                     ExperienceEvidenceCrypto evidenceCrypto,
                                     ExperienceEvidencePdfValidator evidencePdfValidator) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.evidenceRepository = evidenceRepository;
        this.processingRepository = processingRepository;
        this.currentUserService = currentUserService;
        this.evidenceCrypto = evidenceCrypto;
        this.evidencePdfValidator = evidencePdfValidator;
    }

    @GetMapping("/mine")
    public List<ExperienceClaimResponse> myClaims(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        User caller = requireMember();
        return repository.findByUserId(caller.getId(), bounded(page, size)).stream()
                .map(ExperienceClaimResponse::from).toList();
    }

    @GetMapping("/review-queue")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ExperienceClaimResponse> reviewQueue(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return repository.findByStatusIn(List.of("PENDING"), bounded(page, size)).stream()
                .map(ExperienceClaimResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ExperienceClaimResponse getClaim(@PathVariable Long id) {
        return ExperienceClaimResponse.from(requireAccessibleClaim(id));
    }

    @GetMapping("/{id}/history")
    @Transactional(readOnly = true)
    public List<ExperienceClaimAuditResponse> history(@PathVariable Long id) {
        requireAccessibleClaim(id);
        return auditRepository.findByClaimIdOrderByCreatedAtAsc(id).stream()
                .map(ExperienceClaimAuditResponse::from).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ExperienceClaimResponse> createClaim(@Valid @RequestBody ExperienceClaimRequest request) {
        User caller = requireMember();
        var duplicate = repository.findByUserIdAndRequestId(caller.getId(), request.requestId());
        if (duplicate.isPresent()) {
            ExperienceClaim existing = duplicate.get();
            if (!existing.getCompany().equals(request.company().trim())
                    || !existing.getJobTitle().equals(request.jobTitle().trim())
                    || !existing.getSummary().equals(request.summary().trim())
                    || !java.util.Objects.equals(existing.getStartMonth(), blankToNull(request.startMonth()))
                    || !java.util.Objects.equals(existing.getEndMonth(), blankToNull(request.endMonth()))) {
                throw new ResponseStatusException(CONFLICT, "Request ID was already used for another claim");
            }
            return ResponseEntity.status(CREATED).body(ExperienceClaimResponse.from(existing));
        }
        ExperienceClaim claim = new ExperienceClaim();
        claim.setUser(caller);
        claim.setRequestId(request.requestId());
        setClaimFields(claim, request);
        claim.setStatus("AWAITING_EVIDENCE");
        claim.setAiStatus("AWAITING_EVIDENCE");
        claim.setDecisionExplanation("Upload a supporting document to begin evidence analysis.");
        ExperienceClaim saved = repository.saveAndFlush(claim);
        audit(saved, caller, "CLAIM_CREATED", null, "AWAITING_EVIDENCE", null);
        return ResponseEntity.status(CREATED).body(ExperienceClaimResponse.from(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ExperienceClaimResponse updateClaim(@PathVariable Long id,
                                               @Valid @RequestBody ExperienceClaimRequest request) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (!List.of("NEEDS_INFO", "ADDITIONAL_EVIDENCE_REQUIRED", "INCONSISTENT",
                "INSUFFICIENT_EVIDENCE", "PROCESSING_FAILED")
                .contains(claim.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "This claim is not awaiting clarification or reassessment");
        }
        String oldStatus = claim.getStatus();
        setClaimFields(claim, request);
        String nextStatus;
        if (claim.getEvidenceVersion() > 0) {
            nextStatus = "PROCESSING";
            claim.setAiStatus("QUEUED");
            claim.setDecisionExplanation("Updated claim details are queued for evidence analysis.");
            enqueue(claim);
        } else {
            nextStatus = "AWAITING_EVIDENCE";
            claim.setAiStatus("AWAITING_EVIDENCE");
            claim.setDecisionExplanation("Upload a supporting document to begin evidence analysis.");
        }
        claim.setStatus(nextStatus);
        claim.setAiFindings(null);
        claim.setDecisionAt(null);
        claim.setRequestedEvidence(null);
        claim.setAnalysisMethod("NOT_RUN");
        claim.setOcrStatus("NOT_RUN");
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "CLAIM_RESUBMITTED", oldStatus, nextStatus, null);
        return ExperienceClaimResponse.from(saved);
    }

    @PostMapping("/{id}/appeal")
    @Transactional
    public ExperienceClaimResponse appeal(@PathVariable Long id,
                                         @Valid @RequestBody ExperienceClaimAppealRequest request) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (!"REJECTED".equals(claim.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "Only rejected claims can be appealed");
        }
        claim.setAppealNote(request.note().trim());
        String nextStatus;
        if (claim.getEvidenceVersion() > 0) {
            nextStatus = "PROCESSING";
            claim.setAiStatus("QUEUED");
            claim.setDecisionExplanation("Your appeal is queued for reassessment using the submitted evidence.");
            enqueue(claim);
        } else {
            nextStatus = "AWAITING_EVIDENCE";
            claim.setAiStatus("AWAITING_EVIDENCE");
            claim.setDecisionExplanation("Upload supporting evidence so the appeal can be reassessed.");
        }
        claim.setStatus(nextStatus);
        claim.setDecisionAt(null);
        claim.setRequestedEvidence(null);
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "APPEAL_SUBMITTED", "REJECTED", nextStatus, request.note().trim());
        return ExperienceClaimResponse.from(saved);
    }

    @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ExperienceClaimResponse uploadEvidence(@PathVariable Long id,
                                                  @RequestPart("file") MultipartFile file) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String mediaType = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String lowercaseName = name.toLowerCase();
        boolean supportedType = ("application/pdf".equals(mediaType) && lowercaseName.endsWith(".pdf"))
                || ("image/jpeg".equals(mediaType)
                && (lowercaseName.endsWith(".jpg") || lowercaseName.endsWith(".jpeg")))
                || ("image/png".equals(mediaType) && lowercaseName.endsWith(".png"));
        if (file.isEmpty() || file.getSize() > MAX_EVIDENCE_BYTES || !supportedType) {
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE,
                    "Choose a non-empty PDF, JPG, JPEG, or PNG no larger than 5 MiB");
        }
        String cleanedName = StringUtils.cleanPath(name);
        byte[] document;
        try {
            document = file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Evidence upload could not be read");
        }
        if (document.length == 0 || document.length > MAX_EVIDENCE_BYTES
                || !evidencePdfValidator.isValidEvidence(document, mediaType)) {
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Evidence file is invalid or unreadable");
        }
        String hash = sha256(document);
        if (evidenceRepository.findByClaimIdAndEvidenceHash(id, hash).isPresent()) {
            return ExperienceClaimResponse.from(claim);
        }
        if (!List.of("PENDING", "AWAITING_EVIDENCE", "NEEDS_INFO", "PROCESSING",
                "ADDITIONAL_EVIDENCE_REQUIRED", "INCONSISTENT",
                "INSUFFICIENT_EVIDENCE", "PROCESSING_FAILED", "VERIFIED",
                "VERIFIED_FROM_EVIDENCE", "EMPLOYER_CONFIRMED").contains(claim.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "Evidence cannot be changed in the current claim state");
        }
        if (claim.getEvidenceVersion() >= MAX_EVIDENCE_VERSIONS) {
            throw new ResponseStatusException(CONFLICT, "The maximum number of evidence documents was reached");
        }

        byte[] encrypted = evidenceCrypto.encrypt(document);
        int version = claim.getEvidenceVersion() + 1;
        String oldStatus = claim.getStatus();
        String storageKey = UUID.randomUUID().toString();
        String safeName = StringUtils.getFilename(cleanedName);
        evidenceRepository.save(new ExperienceClaimEvidence(
                claim.getId(), version, storageKey, encrypted, safeName, mediaType,
                (long) document.length, hash));
        claim.setEvidenceVersion(version);
        claim.setEvidenceStorageKey(storageKey);
        claim.setEncryptedEvidence(encrypted);
        claim.setEvidenceFileName(safeName);
        claim.setEvidenceMediaType(mediaType);
        claim.setEvidenceSize((long) document.length);
        claim.setEvidenceUploadedAt(LocalDateTime.now());
        claim.setEvidenceHash(hash);
        claim.setAiStatus("QUEUED");
        claim.setAiFindings(null);
        claim.setStatus("PROCESSING");
        claim.setDecisionExplanation("Evidence is queued for automatic analysis.");
        claim.setRequestedEvidence(null);
        claim.setDecisionAt(null);
        claim.setCorroborationStatus("NOT_CHECKED");
        claim.setAnalysisMethod("NOT_RUN");
        claim.setOcrStatus("NOT_RUN");
        enqueue(claim);
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "EVIDENCE_UPLOADED", oldStatus, "PROCESSING",
                "Evidence version " + version + " queued for analysis");
        return ExperienceClaimResponse.from(saved);
    }

    @PostMapping("/{id}/reassess")
    @Transactional
    public ExperienceClaimResponse reassess(@PathVariable Long id) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (!"PROCESSING_FAILED".equals(claim.getStatus()) || claim.getEvidenceVersion() == 0) {
            throw new ResponseStatusException(CONFLICT, "Only failed evidence processing can be retried");
        }
        String oldStatus = claim.getStatus();
        claim.setStatus("PROCESSING");
        claim.setAiStatus("QUEUED");
        claim.setAiFindings(null);
        claim.setDecisionExplanation("Evidence analysis has been queued again.");
        claim.setRequestedEvidence(null);
        claim.setDecisionAt(null);
        claim.setAnalysisMethod("NOT_RUN");
        claim.setOcrStatus("NOT_RUN");
        enqueue(claim);
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "REASSESSMENT_REQUESTED", oldStatus, "PROCESSING", null);
        return ExperienceClaimResponse.from(saved);
    }

    @GetMapping("/{id}/evidence")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> downloadEvidence(@PathVariable Long id) {
        ExperienceClaim claim = requireAccessibleClaim(id);
        if (claim.getEncryptedEvidence() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Evidence not found");
        }
        byte[] document;
        try {
            document = evidenceCrypto.decrypt(claim.getEncryptedEvidence());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Evidence is temporarily unavailable");
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(claim.getEvidenceFileName()).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(claim.getEvidenceMediaType()))
                .contentLength(document.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(document);
    }

    @DeleteMapping("/{id}/evidence")
    @Transactional
    public ResponseEntity<Void> deleteEvidence(@PathVariable Long id) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (claim.getEncryptedEvidence() == null) {
            throw new ResponseStatusException(NOT_FOUND, "Evidence not found");
        }
        String oldStatus = claim.getStatus();
        evidenceRepository.deleteByClaimId(id);
        processingRepository.deleteByClaimId(id);
        claim.setEncryptedEvidence(null);
        claim.setEvidenceStorageKey(null);
        claim.setEvidenceFileName(null);
        claim.setEvidenceMediaType(null);
        claim.setEvidenceSize(null);
        claim.setEvidenceUploadedAt(null);
        claim.setEvidenceHash(null);
        claim.setAiFindings(null);
        claim.setStatus("AWAITING_EVIDENCE");
        claim.setAiStatus("AWAITING_EVIDENCE");
        claim.setDecisionExplanation("Upload a supporting document to begin evidence analysis.");
        claim.setRequestedEvidence(null);
        claim.setDecisionAt(null);
        claim.setCorroborationStatus("NOT_CHECKED");
        claim.setAnalysisMethod("NOT_RUN");
        claim.setOcrStatus("NOT_RUN");
        repository.save(claim);
        audit(claim, caller, "EVIDENCE_DELETED", oldStatus, "AWAITING_EVIDENCE",
                "Evidence deleted by owner");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ExperienceClaimResponse reviewClaim(@PathVariable Long id,
                                               @Valid @RequestBody ExperienceClaimReviewRequest request) {
        ExperienceClaim claim = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        String oldStatus = claim.getStatus();
        String nextStatus = request.status().trim();
        boolean validTransition = ("PENDING".equals(oldStatus)
                && ("NEEDS_INFO".equals(nextStatus) || "REJECTED".equals(nextStatus)))
                || ("NEEDS_INFO".equals(oldStatus) && "REJECTED".equals(nextStatus));
        if (!validTransition) {
            throw new ResponseStatusException(CONFLICT, "Invalid claim status transition");
        }
        User reviewer = currentUserService.requireUser();
        claim.setStatus(nextStatus);
        claim.setReviewerEmail(reviewer.getEmail());
        ExperienceClaim saved = repository.save(claim);
        audit(saved, reviewer, "ADMIN_REVIEW", oldStatus, nextStatus, request.note());
        return ExperienceClaimResponse.from(saved);
    }

    private User requireMember() {
        User caller = currentUserService.requireUser();
        if (!"STUDENT".equalsIgnoreCase(caller.getRole()) && !"ALUMNI".equalsIgnoreCase(caller.getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "Only students and alumni can manage experience claims");
        }
        return caller;
    }

    private ExperienceClaim requireAccessibleClaim(Long id) {
        User caller = currentUserService.requireUser();
        ExperienceClaim claim = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (!currentUserService.isAdmin() && !claim.getUser().getId().equals(caller.getId())) {
            throw new ResponseStatusException(FORBIDDEN, "You may not access this claim");
        }
        return claim;
    }

    private Pageable bounded(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)),
                Sort.by("createdAt").descending());
    }

    private void setClaimFields(ExperienceClaim claim, ExperienceClaimRequest request) {
        claim.setCompany(request.company().trim());
        claim.setJobTitle(request.jobTitle().trim());
        if (claim.getRequestId() == null) claim.setRequestId(request.requestId());
        claim.setStartMonth(blankToNull(request.startMonth()));
        claim.setEndMonth(blankToNull(request.endMonth()));
        claim.setSummary(request.summary().trim());
        claim.setEvidenceSummary(blankToNull(request.evidenceSummary()));
    }

    private void enqueue(ExperienceClaim claim) {
        ExperienceClaimProcessing job = processingRepository.findById(claim.getId())
                .orElseGet(() -> new ExperienceClaimProcessing(
                        claim.getId(), claim.getEvidenceVersion(), LocalDateTime.now()));
        job.setEvidenceVersion(claim.getEvidenceVersion());
        job.setAttemptCount(0);
        job.setNextAttemptAt(LocalDateTime.now());
        job.setLeaseUntil(null);
        job.setLastError(null);
        job.setUpdatedAt(LocalDateTime.now());
        processingRepository.save(job);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void audit(ExperienceClaim claim, User actor, String event, String oldStatus,
                       String newStatus, String note) {
        auditRepository.save(new ExperienceClaimAudit(claim.getId(), actor.getEmail(),
                event, oldStatus, newStatus, note));
    }

    private String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
