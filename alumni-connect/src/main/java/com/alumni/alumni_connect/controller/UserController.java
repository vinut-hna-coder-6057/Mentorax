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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@RestController

@RequestMapping("/users")

public class UserController {

    private final UserRepository repository;
    private final StudentProfileRepository studentProfileRepository;
    private final AlumniProfileRepository alumniProfileRepository;
    private final CurrentUserService currentUserService;

    public UserController(
            UserRepository repository,
            StudentProfileRepository studentProfileRepository,
            AlumniProfileRepository alumniProfileRepository,
            CurrentUserService currentUserService
    ) {
        this.repository = repository;
        this.studentProfileRepository = studentProfileRepository;
        this.alumniProfileRepository = alumniProfileRepository;
        this.currentUserService = currentUserService;
    }

    // =========================================
    // GET ALL APPROVED ALUMNI
    // =========================================

@GetMapping("/alumni")
public List<UserProfileResponse> getAllAlumni(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
    Pageable pageable = bounded(page, size);
    return repository.findByRoleAndStatus(
            "ALUMNI",
            "APPROVED", pageable
    ).stream()
            .map(UserProfileResponse::from)
            .toList();
}
    // =========================================
    // GET ALL APPROVED STUDENTS
    // @GetMapping("/alumni")=========================================

    @GetMapping("/students")
public List<UserProfileResponse> getAllStudents(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
    Pageable pageable = bounded(page, size);
    return repository.findByRoleAndStatus(
            "STUDENT",
            "APPROVED", pageable
    ).stream()
            .map(UserProfileResponse::from)
            .toList();
}

    // =========================================
    // GET USER BY ID
    // =========================================

    @GetMapping("/{id}")
    public UserProfileResponse getUserById(
            @PathVariable Long id
    ) {

        return repository.findById(id)
                .map(UserProfileResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // =========================================
    // GET USER BY EMAIL
    // =========================================

    @GetMapping("/email/{email}")
    public UserProfileResponse getUserByEmail(
            @PathVariable String email
    ) {

        return repository.findByEmail(email)
                .map(UserProfileResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
    // =========================================
// GET ALL USERS
// =========================================
@GetMapping
@PreAuthorize("hasRole('ADMIN')")
public List<UserProfileResponse> getAllUsers(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
    return repository.findAllBy(bounded(page, size)).stream()
            .map(UserProfileResponse::from)
            .toList();
}

    private Pageable bounded(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)), Sort.by("id").ascending());
    }
    // =========================================
    // UPDATE PROFILE
    // =========================================

    @PutMapping("/{id}")

    public UserProfileResponse updateProfile(

            @PathVariable Long id,

            @jakarta.validation.Valid @RequestBody UserProfileUpdateRequest updatedUser

    ) {

        currentUserService.requireOwnerOrAdmin(id);

        User user = repository

                .findById(id)

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // BASIC INFO

        user.setName(
                updatedUser.name()
        );

        user.setCollege(
                updatedUser.college()
        );

        user.setBranch(
                updatedUser.branch()
        );

        user.setPassoutYear(
                updatedUser.passoutYear()
        );

        user.setRollno(
                updatedUser.rollno()
        );

        user.setSection(
                updatedUser.section()
        );

        // PROFILE INFO

        user.setBio(
                updatedUser.bio()
        );

        user.setCompany(
                updatedUser.company()
        );

        user.setJobRole(
                updatedUser.jobRole()
        );

        user.setLinkedin(
                updatedUser.linkedin()
        );

        user.setGithub(
                updatedUser.github()
        );

        user.setProfileImage(
                updatedUser.profileImage()
        );

        user.setInterests(
                updatedUser.interests()
        );

        user.setLocation(
                updatedUser.location()
        );

        User saved = repository.save(user);

        // Keep the normalized role profile authoritative in parallel with the
        // legacy response fields until the Angular API can move to profile DTOs.
        if ("STUDENT".equalsIgnoreCase(saved.getRole())) {
            StudentProfile profile = studentProfileRepository.findById(saved.getId())
                    .orElseGet(() -> new StudentProfile(saved));
            profile.copyLegacyFields(saved);
            studentProfileRepository.save(profile);
        } else if ("ALUMNI".equalsIgnoreCase(saved.getRole())) {
            AlumniProfile profile = alumniProfileRepository.findById(saved.getId())
                    .orElseGet(() -> new AlumniProfile(saved));
            profile.copyLegacyFields(saved);
            alumniProfileRepository.save(profile);
        }

        return UserProfileResponse.from(saved);
    }
}
