package com.alumni.alumni_connect.dto;
import jakarta.validation.constraints.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlumniCreateRequest(@NotBlank @Size(max=120) String name, @NotBlank @Email @Size(max=255) String email,
        @NotBlank @Size(min=8,max=128) String password, @Size(max=200) String college, @Size(max=100) String branch,
        @Size(max=20) String passoutYear, @Size(max=50) String rollno, @Size(max=50) String section,
        @Size(max=1000) String bio, @Size(max=255) String skills, @Size(max=150) String company,
        @Size(max=150) String jobRole, @Size(max=255) String linkedin, @Size(max=255) String github,
        @Size(max=255) String profileImage, @Size(max=255) String interests, @Size(max=150) String location) {
    public com.alumni.alumni_connect.entity.User toUser() {
        var u = new SignupRequest(name,email,password,"ALUMNI",college,branch,passoutYear,rollno,section,bio,skills,company,jobRole,linkedin,github,profileImage,interests,location).toUser();
        u.setStatus("PENDING"); return u;
    }
}
