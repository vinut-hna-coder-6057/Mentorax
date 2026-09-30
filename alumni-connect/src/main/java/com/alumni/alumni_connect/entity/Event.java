package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity

@Table(name = "events")

public class Event {

    // =====================================
    // ID
    // =====================================

    @Id

    @GeneratedValue(
            strategy =
                    GenerationType.IDENTITY
    )

    private Long id;

    // =====================================
    // EVENT DETAILS
    // =====================================
@NotBlank
@Size(max = 255)
@Column(nullable = false)
private String title;
@Size(max = 5000)
@Column(length = 5000)
private String description;
@Size(max = 255)
private String location;
@Size(max = 100)
private String eventDate;
@Size(max = 32)
@Column(length = 32)
private String role;
@Size(max = 32)
@Column(length = 32)
private String status = "PENDING";
    // =====================================
    // CREATOR
    // =====================================

    @Column(name = "created_by_email")
    private String createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User creator;

   

    // =====================================
    // APPROVAL WORKFLOW
    // =====================================

  

    // =====================================
    // RSVP COUNT
    // =====================================

    private int attendeeCount = 0;

    // =====================================
    // OPTIONAL FEATURES
    // =====================================

    @Size(max = 100)
private String category;

   @Size(max = 2000)
@Column(length = 2000)
private String meetingLink;
@Size(max = 3000)
@Column(length = 3000)
private String imageUrl;
    // =====================================
    // CREATED TIME
    // =====================================

    private LocalDateTime createdAt;

    // =====================================
    // CONSTRUCTOR
    // =====================================

    public Event() {
    }

    // =====================================
    // ID
    // =====================================

    public Long getId() {

        return id;
    }

    public void setId(Long id) {

        this.id = id;
    }

    // =====================================
    // TITLE
    // =====================================

    public String getTitle() {

        return title;
    }

    public void setTitle(String title) {

        this.title = title;
    }

    // =====================================
    // DESCRIPTION
    // =====================================

    public String getDescription() {

        return description;
    }

    public void setDescription(

            String description

    ) {

        this.description =
                description;
    }

    // =====================================
    // LOCATION
    // =====================================

    public String getLocation() {

        return location;
    }

    public void setLocation(

            String location

    ) {

        this.location =
                location;
    }

    // =====================================
    // EVENT DATE
    // =====================================

    public String getEventDate() {

        return eventDate;
    }

    public void setEventDate(

            String eventDate

    ) {

        this.eventDate =
                eventDate;
    }

    // =====================================
    // CREATED BY
    // =====================================

    public String getCreatedBy() {

        return createdBy;
    }

    public void setCreatedBy(

            String createdBy

    ) {

        this.createdBy =
                createdBy;
    }

    public User getCreator() { return creator; }
    public void setCreator(User creator) {
        this.creator = creator;
        if (creator != null) this.createdBy = creator.getEmail();
    }

    // =====================================
    // ROLE
    // =====================================

    public String getRole() {

        return role;
    }

    public void setRole(

            String role

    ) {

        this.role =
                role;
    }

    // =====================================
    // STATUS
    // =====================================

    public String getStatus() {

        return status;
    }

    public void setStatus(

            String status

    ) {

        this.status =
                status;
    }

    // =====================================
    // ATTENDEE COUNT
    // =====================================

    public int getAttendeeCount() {

        return attendeeCount;
    }

    public void setAttendeeCount(

            int attendeeCount

    ) {

        this.attendeeCount =
                attendeeCount;
    }

    // =====================================
    // CATEGORY
    // =====================================

    public String getCategory() {

        return category;
    }

    public void setCategory(

            String category

    ) {

        this.category =
                category;
    }

    // =====================================
    // MEETING LINK
    // =====================================

    public String getMeetingLink() {

        return meetingLink;
    }

    public void setMeetingLink(

            String meetingLink

    ) {

        this.meetingLink =
                meetingLink;
    }

    // =====================================
    // IMAGE URL
    // =====================================

    public String getImageUrl() {

        return imageUrl;
    }

    public void setImageUrl(

            String imageUrl

    ) {

        this.imageUrl =
                imageUrl;
    }

    // =====================================
    // CREATED AT
    // =====================================

    public LocalDateTime getCreatedAt() {

        return createdAt;
    }

    public void setCreatedAt(

            LocalDateTime createdAt

    ) {

        this.createdAt =
                createdAt;
    }
}
