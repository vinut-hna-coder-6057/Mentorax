package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "alumni_profiles")
public class AlumniProfile {

    @Id
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;

    private Integer graduationYear;
    private String branch;
    private String college;
    private String company;
    private String jobTitle;
    private String linkedinUrl;
    private String githubUrl;

    @Column(length = 1000)
    private String bio;

    private String location;
    @Column(length = 32)
    private String approvalStatus = "PENDING";
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected AlumniProfile() {
    }

    public AlumniProfile(User user) {
        this.user = user;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void copyLegacyFields(User source) {
        branch = source.getBranch();
        college = source.getCollege();
        company = source.getCompany();
        jobTitle = source.getJobRole();
        linkedinUrl = source.getLinkedin();
        githubUrl = source.getGithub();
        bio = source.getBio();
        location = source.getLocation();
        try {
            graduationYear = source.getPassoutYear() == null ? null : Integer.valueOf(source.getPassoutYear());
        } catch (NumberFormatException ignored) {
            graduationYear = null;
        }
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
