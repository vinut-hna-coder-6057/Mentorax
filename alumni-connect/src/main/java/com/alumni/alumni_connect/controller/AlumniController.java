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

@RestController
@RequestMapping("/alumni")
public class AlumniController {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AlumniController(
            UserRepository repository,
            PasswordEncoder passwordEncoder
    ) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
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

        return UserProfileResponse.from(
                repository.save(alumni)
        );
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

        return UserProfileResponse.from(
                repository.save(alumni)
        );
    }

    // =====================================================
    // DELETE ALUMNI
    // =====================================================

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteAlumni(
            @PathVariable Long id
    ) {
        repository.deleteById(id);
    }

    // =====================================================
    // APPROVE ALUMNI
    // =====================================================

    @PutMapping("/approve/{id}")
    @PreAuthorize("hasRole('ADMIN')")
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

        return UserProfileResponse.from(
                repository.save(alumni)
        );
    }
}
