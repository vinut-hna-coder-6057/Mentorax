package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.User;

/** Professional profile fields intended for the authenticated community directory. */
public record PublicDirectoryProfileResponse(
        Long id,
        String name,
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
    public static PublicDirectoryProfileResponse from(User user) {
        return new PublicDirectoryProfileResponse(
                user.getId(),
                user.getName(),
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
