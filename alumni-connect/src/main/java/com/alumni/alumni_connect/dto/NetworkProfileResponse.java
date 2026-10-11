package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.User;

/** Limited profile shared through the caller's own connection list. */
public record NetworkProfileResponse(
        Long id,
        String name,
        String email,
        String role,
        String college,
        String branch,
        String passoutYear,
        String bio,
        String skills,
        String company,
        String jobRole,
        String linkedin,
        String github,
        String profileImage,
        String interests
) {
    public static NetworkProfileResponse from(User user, boolean shareEmail) {
        return new NetworkProfileResponse(
                user.getId(),
                user.getName(),
                shareEmail ? user.getEmail() : null,
                user.getRole(),
                user.getCollege(),
                user.getBranch(),
                user.getPassoutYear(),
                user.getBio(),
                user.getSkills(),
                user.getCompany(),
                user.getJobRole(),
                user.getLinkedin(),
                user.getGithub(),
                user.getProfileImage(),
                user.getInterests());
    }
}
