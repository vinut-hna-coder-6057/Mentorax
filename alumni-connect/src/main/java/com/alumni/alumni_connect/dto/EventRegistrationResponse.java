package com.alumni.alumni_connect.dto;
import com.alumni.alumni_connect.entity.EventRegistration;
import java.time.LocalDateTime;
public record EventRegistrationResponse(Long id, Long eventId, String studentEmail, UserProfileResponse user, LocalDateTime registeredAt) {
    public static EventRegistrationResponse from(EventRegistration r) { return new EventRegistrationResponse(r.getId(),r.getEventId(),r.getStudentEmail(),UserProfileResponse.from(r.getUser()),r.getRegisteredAt()); }
}
