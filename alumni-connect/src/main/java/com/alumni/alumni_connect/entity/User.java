package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.alumni.alumni_connect.util.EmailAddress;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // BASIC INFO
    private String name;

    @Column(unique = true, nullable = false)
    private String email;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(name = "password_hash", nullable = false)
    private String password;

    private String role;

    private String status;
    private boolean emailVerified;
    // COLLEGE INFO
    private String college;

    private String passoutYear;

    private String rollno;

    private String section;

    private String branch;

    // PROFILE INFO
    @Column(length = 1000)
    private String bio;

    private String skills;

    private String company;

    private String jobRole;

    private String linkedin;

    private String github;

    private String profileImage;

    private String interests;

    private String location;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    @JsonIgnore
    private StudentProfile studentProfile;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    @JsonIgnore
    private AlumniProfile alumniProfile;

    public User() {}

    public User(
            String name,
            String email,
            String password,
            String role,
            String status
    ) {
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
        this.status = status;
    }
    public boolean isEmailVerified() {
    return emailVerified;
}

public void setEmailVerified(boolean emailVerified) {
    this.emailVerified = emailVerified;
}
    // ID
    public Long getId() {
        return id;
    }

    // NAME
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    // EMAIL
    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = EmailAddress.normalize(email);
    }

    // PASSWORD
    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    // ROLE
    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    // STATUS
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    // COLLEGE
    public String getCollege() {
        return college;
    }

    public void setCollege(String college) {
        this.college = college;
    }

    // PASSOUT YEAR
    public String getPassoutYear() {
        return passoutYear;
    }

    public void setPassoutYear(String passoutYear) {
        this.passoutYear = passoutYear;
    }

    // ROLL NUMBER
    public String getRollno() {
        return rollno;
    }

    public void setRollno(String rollno) {
        this.rollno = rollno;
    }

    // SECTION
    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    // BRANCH
    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    // BIO
    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    // SKILLS
    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }

    // COMPANY
    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    // JOB ROLE
    public String getJobRole() {
        return jobRole;
    }

    public void setJobRole(String jobRole) {
        this.jobRole = jobRole;
    }

    // LINKEDIN
    public String getLinkedin() {
        return linkedin;
    }

    public void setLinkedin(String linkedin) {
        this.linkedin = linkedin;
    }

    // GITHUB
    public String getGithub() {
        return github;
    }

    public void setGithub(String github) {
        this.github = github;
    }

    // PROFILE IMAGE
    public String getProfileImage() {
        return profileImage;
    }

    public void setProfileImage(String profileImage) {
        this.profileImage = profileImage;
    }

    // INTERESTS
    public String getInterests() {
        return interests;
    }

    public void setInterests(String interests) {
        this.interests = interests;
    }

    // LOCATION
    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
