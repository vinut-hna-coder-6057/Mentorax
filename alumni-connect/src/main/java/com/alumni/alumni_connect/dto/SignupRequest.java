package com.alumni.alumni_connect.dto;

import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SignupRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 128) String password, @NotBlank @Pattern(regexp = "(?i)STUDENT|ALUMNI") String role,
        @Size(max = 200) String college, @Size(max = 100) String branch, @Size(max = 20) String passoutYear,
        @Size(max = 50) String rollno, @Size(max = 50) String section, @Size(max = 1000) String bio,
        @Size(max = 255) String skills, @Size(max = 150) String company, @Size(max = 150) String jobRole,
        @Size(max = 255) String linkedin, @Size(max = 255) String github, @Size(max = 255) String profileImage,
        @Size(max = 255) String interests, @Size(max = 150) String location) {
    public com.alumni.alumni_connect.entity.User toUser() {
        var u = new com.alumni.alumni_connect.entity.User();
        u.setName(name); u.setEmail(email); u.setPassword(password); u.setRole(role.toUpperCase());
        u.setCollege(college); u.setBranch(branch); u.setPassoutYear(passoutYear); u.setRollno(rollno); u.setSection(section);
        u.setBio(bio); u.setSkills(skills); u.setCompany(company); u.setJobRole(jobRole); u.setLinkedin(linkedin);
        u.setGithub(github); u.setProfileImage(profileImage); u.setInterests(interests); u.setLocation(location); return u;
    }
}
