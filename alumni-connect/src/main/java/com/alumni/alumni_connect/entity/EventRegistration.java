package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity

@Table(
    name = "event_registrations",
    uniqueConstraints = {
        @UniqueConstraint(
                columnNames = {"event_id", "student_email"}
            ),
            @UniqueConstraint(
                columnNames = {"event_id", "user_id"}
        )
    }
)

public class EventRegistration {

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
    // EVENT ID
    // =====================================

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // =====================================
    // STUDENT EMAIL
    // =====================================

    @Column(name = "student_email")
    private String studentEmail;

    // =====================================
    // REGISTERED TIME
    // =====================================

    private LocalDateTime registeredAt;

    // =====================================
    // GETTERS + SETTERS
    // =====================================

    public Long getId() {

        return id;
    }

    public void setId(Long id) {

        this.id = id;
    }

    // =====================================
    // EVENT ID
    // =====================================

    public Long getEventId() {

        return eventId;
    }

    public void setEventId(Long eventId) {

        this.eventId = eventId;
    }

    public Event getEvent() {
        return event;
    }

    public void setEvent(Event event) {
        this.event = event;
        this.eventId = event == null ? null : event.getId();
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
        this.studentEmail = user == null ? null : user.getEmail();
    }

    // =====================================
    // STUDENT EMAIL
    // =====================================

    public String getStudentEmail() {

        return studentEmail;
    }

    public void setStudentEmail(

            String studentEmail

    ) {

        this.studentEmail =
                studentEmail;
    }

    // =====================================
    // REGISTERED AT
    // =====================================

    public LocalDateTime getRegisteredAt() {

        return registeredAt;
    }

    public void setRegisteredAt(

            LocalDateTime registeredAt

    ) {

        this.registeredAt =
                registeredAt;
    }
}

