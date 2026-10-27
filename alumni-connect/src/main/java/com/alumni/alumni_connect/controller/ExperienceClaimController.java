package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceClaimAudit;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ExperienceClaimAuditRepository;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.alumni.alumni_connect.service.ExperienceEvidenceAnalysisService;
import com.alumni.alumni_connect.service.ExperienceEvidenceCrypto;
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
import java.nio.charset.StandardCharsets;
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
    private final ExperienceClaimRepository repository;
    private final ExperienceClaimAuditRepository auditRepository;
    private final CurrentUserService currentUserService;
    private final ExperienceEvidenceCrypto evidenceCrypto;
    private final ExperienceEvidenceAnalysisService analysisService;

    public ExperienceClaimController(ExperienceClaimRepository repository,
                                     ExperienceClaimAuditRepository auditRepository,
                                     CurrentUserService currentUserService,
                                     ExperienceEvidenceCrypto evidenceCrypto,
                                     ExperienceEvidenceAnalysisService analysisService) {
        this.repository = repository;
        this.auditRepository = auditRepository;
        this.currentUserService = currentUserService;
        this.evidenceCrypto = evidenceCrypto;
        this.analysisService = analysisService;
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
        return repository.findByStatusIn(List.of("PENDING", "NEEDS_INFO"), bounded(page, size)).stream()
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
        claim.setStatus("PENDING");
        claim.setAiStatus("PENDING");
        ExperienceClaim saved = repository.saveAndFlush(claim);
        audit(saved, caller, "CLAIM_CREATED", null, "PENDING", null);
        return ResponseEntity.status(CREATED).body(ExperienceClaimResponse.from(saved));
    }

    @PutMapping("/{id}")
    @Transactional
    public ExperienceClaimResponse updateClaim(@PathVariable Long id,
                                               @Valid @RequestBody ExperienceClaimRequest request) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (!"NEEDS_INFO".equals(claim.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "Only claims requesting more information can be edited");
        }
        String oldStatus = claim.getStatus();
        setClaimFields(claim, request);
        claim.setStatus("PENDING");
        claim.setAiStatus("PENDING");
        claim.setAiFindings(null);
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "CLAIM_RESUBMITTED", oldStatus, "PENDING", null);
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
        claim.setStatus("PENDING");
        claim.setAiStatus("PENDING");
        String oldStatus = "REJECTED";
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "APPEAL_SUBMITTED", oldStatus, "PENDING", request.note().trim());
        return ExperienceClaimResponse.from(saved);
    }

    @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ExperienceClaimResponse uploadEvidence(@PathVariable Long id,
                                                  @RequestPart("file") MultipartFile file) {
        User caller = requireMember();
        ExperienceClaim claim = repository.findByIdAndUserId(id, caller.getId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Claim not found"));
        if (!"PENDING".equals(claim.getStatus()) && !"NEEDS_INFO".equals(claim.getStatus())) {
            throw new ResponseStatusException(CONFLICT, "Evidence cannot be changed in the current claim state");
        }
        String name = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        if (file.isEmpty() || file.getSize() > MAX_EVIDENCE_BYTES || !name.toLowerCase().endsWith(".pdf")
                || !"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE,
                    "Choose a non-empty PDF no larger than 5 MiB");
        }
        byte[] document;
        try {
            document = file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(BAD_REQUEST, "Evidence upload could not be read");
        }
        if (document.length == 0 || document.length > MAX_EVIDENCE_BYTES
                || !new String(document, 0, Math.min(document.length, 5), StandardCharsets.US_ASCII).startsWith("%PDF-")) {
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Evidence is not a valid PDF file");
        }

        byte[] encrypted = evidenceCrypto.encrypt(document);
        String oldHash = claim.getEvidenceHash();
        String oldStatus = claim.getStatus();
        claim.setEvidenceStorageKey(UUID.randomUUID().toString());
        claim.setEncryptedEvidence(encrypted);
        claim.setEvidenceFileName(name.substring(name.lastIndexOf('/') + 1));
        claim.setEvidenceMediaType("application/pdf");
        claim.setEvidenceSize((long) document.length);
        claim.setEvidenceUploadedAt(LocalDateTime.now());
        claim.setEvidenceHash(sha256(document));
        ExperienceEvidenceAnalysisRequest request = new ExperienceEvidenceAnalysisRequest(
                claim.getCompany(), claim.getJobTitle(), claim.getStartMonth(),
                claim.getEndMonth(), claim.getSummary());
        ExperienceEvidenceAnalysisService.AnalysisResult analysis =
                analysisService.analyze(request, document, claim.getEvidenceFileName());
        claim.setAiStatus(analysis.status());
        claim.setAiFindings(analysis.findings());
        claim.setStatus("PENDING");
        ExperienceClaim saved = repository.save(claim);
        audit(saved, caller, "EVIDENCE_UPLOADED", oldStatus, "PENDING",
                oldHash == null ? "Evidence attached" : "Evidence replaced");
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
                .contentType(MediaType.APPLICATION_PDF)
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
        claim.setEncryptedEvidence(null);
        claim.setEvidenceStorageKey(null);
        claim.setEvidenceFileName(null);
        claim.setEvidenceMediaType(null);
        claim.setEvidenceSize(null);
        claim.setEvidenceUploadedAt(null);
        claim.setEvidenceHash(null);
        claim.setAiFindings(null);
        claim.setAiStatus("PENDING");
        repository.save(claim);
        audit(claim, caller, "EVIDENCE_DELETED", claim.getStatus(), claim.getStatus(), "Evidence deleted by owner");
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
