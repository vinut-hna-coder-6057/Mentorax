package com.alumni.alumni_connect.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EventRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 5000) String description,
        @Size(max = 255) String location,
        @Size(max = 100) String eventDate,
        @Size(max = 100) String category,
        @Size(max = 2000) String meetingLink,
        @Size(max = 3000) String imageUrl
) {}
