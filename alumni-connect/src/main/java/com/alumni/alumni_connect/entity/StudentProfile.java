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
@Table(name = "student_profiles")
public class StudentProfile {

    @Id
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    @JsonIgnore
    private User user;

    @Column(unique = true)
    private String rollNumber;

    private String branch;
    private String section;
    private Integer batchYear;
    private String college;

    @Column(length = 1000)
    private String bio;

    private String location;
    @Column(length = 32)
    private String verificationStatus = "PENDING";
    private Long verifiedBy;
    private LocalDateTime verifiedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected StudentProfile() {
    }

    public StudentProfile(User user) {
        this.user = user;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public void copyLegacyFields(User source) {
        rollNumber = source.getRollno();
        branch = source.getBranch();
        section = source.getSection();
        college = source.getCollege();
        bio = source.getBio();
        location = source.getLocation();
        try {
            batchYear = source.getPassoutYear() == null ? null : Integer.valueOf(source.getPassoutYear());
        } catch (NumberFormatException ignored) {
            batchYear = null;
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
