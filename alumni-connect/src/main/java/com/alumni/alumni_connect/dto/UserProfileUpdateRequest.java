package com.alumni.alumni_connect.dto;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserProfileUpdateRequest(@NotBlank @Size(max=120) String name, @Size(max=200) String college,
        @Size(max=100) String branch, @Size(max=20) String passoutYear, @Size(max=50) String rollno,
        @Size(max=50) String section, @Size(max=1000) String bio, @Size(max=255) String skills,
        @Size(max=150) String company, @Size(max=150) String jobRole, @Size(max=255) String linkedin,
        @Size(max=255) String github, @Size(max=255) String profileImage, @Size(max=255) String interests,
        @Size(max=150) String location) {}
