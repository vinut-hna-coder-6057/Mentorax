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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

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
    public List<User> getAllAlumni() {

        return repository.findByRoleAndStatus(
                "ALUMNI",
                "APPROVED"
        );
    }

    // =========================================
    // GET ALL APPROVED STUDENTS
    // =========================================

    @GetMapping("/students")
    public List<User> getAllStudents() {

        return repository.findByRoleAndStatus(
                "STUDENT",
                "APPROVED"
        );
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
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public List<User> getAllUsers() {

        return repository.findAll();
    }

    // =========================================
    // UPDATE PROFILE
    // =========================================

    @PutMapping("/{id}")

    public User updateProfile(

            @PathVariable Long id,

            @RequestBody User updatedUser

    ) {

        currentUserService.requireOwnerOrAdmin(id);

        User user = repository

                .findById(id)

                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // BASIC INFO

        user.setName(
                updatedUser.getName()
        );

        user.setCollege(
                updatedUser.getCollege()
        );

        user.setBranch(
                updatedUser.getBranch()
        );

        user.setPassoutYear(
                updatedUser.getPassoutYear()
        );

        user.setRollno(
                updatedUser.getRollno()
        );

        user.setSection(
                updatedUser.getSection()
        );

        // PROFILE INFO

        user.setBio(
                updatedUser.getBio()
        );

        user.setCompany(
                updatedUser.getCompany()
        );

        user.setJobRole(
                updatedUser.getJobRole()
        );

        user.setLinkedin(
                updatedUser.getLinkedin()
        );

        user.setGithub(
                updatedUser.getGithub()
        );

        user.setProfileImage(
                updatedUser.getProfileImage()
        );

        user.setInterests(
                updatedUser.getInterests()
        );

        user.setLocation(
                updatedUser.getLocation()
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

        return saved;
    }
}
