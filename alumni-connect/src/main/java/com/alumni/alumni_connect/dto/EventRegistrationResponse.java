package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.entity.EventRegistration;
import com.alumni.alumni_connect.entity.User;
import java.time.LocalDateTime;

public record EventRegistrationResponse(
        Long id,
        Long eventId,
        String studentEmail,
        AttendeeProfile user,
        LocalDateTime registeredAt
) {
    public static EventRegistrationResponse from(EventRegistration registration) {
        return new EventRegistrationResponse(
                registration.getId(),
                registration.getEventId(),
                registration.getStudentEmail(),
                AttendeeProfile.from(registration.getUser()),
                registration.getRegisteredAt());
    }

    public record AttendeeProfile(Long id, String name) {
        private static AttendeeProfile from(User user) {
            return new AttendeeProfile(user.getId(), user.getName());
        }
    }
}
