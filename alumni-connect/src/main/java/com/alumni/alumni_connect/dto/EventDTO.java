package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

public record EventDTO(Long id, String title, String description, String location, String eventDate, String role,
        String status, String createdBy, int attendeeCount, Integer maxCapacity, String category, String meetingLink, String imageUrl,
        java.time.LocalDateTime createdAt) {
    public static EventDTO from(com.alumni.alumni_connect.entity.Event e) {
        return from(e, true);
    }

    public static EventDTO from(com.alumni.alumni_connect.entity.Event e, boolean includeMeetingLink) {
        return new EventDTO(
                e.getId(),
                e.getTitle(),
                e.getDescription(),
                e.getLocation(),
                e.getEventDate(),
                e.getRole(),
                e.getStatus(),
                e.getCreatedBy(),
                e.getAttendeeCount(),
                e.getMaxCapacity(),
                e.getCategory(),
                includeMeetingLink ? e.getMeetingLink() : null,
                e.getImageUrl(),
                e.getCreatedAt());
    }
}
