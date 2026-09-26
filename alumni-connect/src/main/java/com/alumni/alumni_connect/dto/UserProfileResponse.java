package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.User;

public record UserProfileResponse(
        Long id,
        String name,
        String email,
        String role,
        String status,
        String college,
        String branch,
        String passoutYear,
        String rollno,
        String section,
        String bio,
        String skills,
        String company,
        String jobRole,
        String linkedin,
        String github,
        String profileImage,
        String interests,
        String location
) {
    public static UserProfileResponse from(User user) {
        return new UserProfileResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.getCollege(),
                user.getBranch(),
                user.getPassoutYear(),
                user.getRollno(),
                user.getSection(),
                user.getBio(),
                user.getSkills(),
                user.getCompany(),
                user.getJobRole(),
                user.getLinkedin(),
                user.getGithub(),
                user.getProfileImage(),
                user.getInterests(),
                user.getLocation());
    }
}
