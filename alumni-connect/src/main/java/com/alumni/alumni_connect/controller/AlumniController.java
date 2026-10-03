package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

@RestController
@RequestMapping("/alumni")
public class AlumniController {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final AlumniProfileRepository alumniProfileRepository;
    private final OtpService otpService;
    private final EmailOutboxService emailOutboxService;
    private final CurrentUserService currentUserService;

    public AlumniController(
            UserRepository repository,
            PasswordEncoder passwordEncoder,
            AlumniProfileRepository alumniProfileRepository,
            OtpService otpService,
            EmailOutboxService emailOutboxService,
            CurrentUserService currentUserService
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.alumniProfileRepository = alumniProfileRepository;
        this.otpService = otpService;
        this.emailOutboxService = emailOutboxService;
        this.currentUserService = currentUserService;
    }

    // =====================================================
    // GET ALL APPROVED ALUMNI
    // =====================================================

    @GetMapping
    public List<UserProfileResponse> getAllApprovedAlumni(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return repository.findByRoleAndStatus(
                "ALUMNI",
                "APPROVED", bounded(page,size)
        ).stream()
                .map(UserProfileResponse::from)
                .toList();
    }

    // =====================================================
    // ADD ALUMNI
    // =====================================================

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserProfileResponse addAlumni(
            @Valid @RequestBody AlumniCreateRequest request
    ) {
        User alumni = request.toUser();
        alumni.setRole("ALUMNI");
        alumni.setStatus("PENDING");

        if (alumni.getPassword() == null || alumni.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }

        alumni.setPassword(passwordEncoder.encode(alumni.getPassword()));
        alumni.setEmailVerified(false);
        User saved = repository.save(alumni);
        AlumniProfile profile = new AlumniProfile(saved);
        profile.copyLegacyFields(saved);
        profile.setApprovalStatus(saved.getStatus());
        alumniProfileRepository.save(profile);

        String otp = otpService.generateOtp(saved.getEmail(), "EMAIL_VERIFICATION");
        emailOutboxService.enqueueVerification(saved.getEmail(), otp);
        return UserProfileResponse.from(saved);
    }

    // =====================================================
    // GET APPROVED ALUMNI
    // =====================================================

    @GetMapping("/approved")
    public List<UserProfileResponse> getApprovedAlumni(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return repository.findByRoleAndStatus(
                "ALUMNI",
                "APPROVED", bounded(page,size)
        ).stream()
                .map(UserProfileResponse::from)
                .toList();
    }

    // =====================================================
    // GET PENDING ALUMNI
    // =====================================================

    @GetMapping("/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserProfileResponse> getPendingAlumni(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return repository.findByRoleAndStatus(
                "ALUMNI",
                "PENDING", bounded(page,size)
        ).stream()
                .map(UserProfileResponse::from)
                .toList();
    }

    private Pageable bounded(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), Sort.by("id").ascending());
    }

    // =====================================================
    // REJECT ALUMNI
    // =====================================================

    @PutMapping("/reject/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserProfileResponse rejectAlumni(@PathVariable Long id) {

        User alumni = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (!"ALUMNI".equalsIgnoreCase(alumni.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only alumni accounts can be rejected"
            );
        }

        alumni.setStatus("REJECTED");
        syncAlumniProfile(alumni);

        return UserProfileResponse.from(
                repository.save(alumni)
        );
    }

    // =====================================================
    // DELETE ALUMNI
    // =====================================================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void deleteAlumni(
            @PathVariable Long id
    ) {
        User alumni = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (currentUserService.requireUser().getId().equals(alumni.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Administrators cannot delete their own account");
        }
        if (!"ALUMNI".equalsIgnoreCase(alumni.getRole())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only alumni accounts can be deleted here");
        }

        // Remove the dependent role profile first. Other foreign keys intentionally
        // restrict deletion so unrelated messages, registrations, or notifications
        // are never cascaded away.
        alumniProfileRepository.deleteById(alumni.getId());
        try {
            repository.delete(alumni);
            repository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Account is still referenced by application data and cannot be deleted");
        }
    }

    // =====================================================
    // APPROVE ALUMNI
    // =====================================================

    @PutMapping("/approve/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public UserProfileResponse approveAlumni(@PathVariable Long id) {

        User alumni = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"
                ));

        if (!"ALUMNI".equalsIgnoreCase(alumni.getRole())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only alumni accounts can be approved"
            );
        }

        alumni.setStatus("APPROVED");
        syncAlumniProfile(alumni);

        return UserProfileResponse.from(
                repository.save(alumni)
        );
    }

    private void syncAlumniProfile(User alumni) {
        AlumniProfile profile = alumniProfileRepository.findById(alumni.getId())
                .orElseGet(() -> {
                    AlumniProfile created = new AlumniProfile(alumni);
                    created.copyLegacyFields(alumni);
                    return created;
                });
        profile.setApprovalStatus(alumni.getStatus());
        if ("APPROVED".equalsIgnoreCase(alumni.getStatus())) {
            profile.setApprovedAt(java.time.LocalDateTime.now());
        } else {
            profile.setApprovedAt(null);
            profile.setApprovedBy(null);
        }
        alumniProfileRepository.save(profile);
    }
}
