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
@Table(name = "notifications")

public class Notification {

    @Id

    @GeneratedValue(
            strategy =
                    GenerationType.IDENTITY
    )

    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User actor;

    private String email;

    @Column(length = 1000)

    private String message;

    private boolean isRead = false;

    @Column(length = 64)
    private String type;

    @Column(length = 1000)
    private String linkUrl;

    private LocalDateTime timestamp;

    public Notification() {}

    // =====================================
    // GETTERS & SETTERS
    // =====================================

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public User getRecipient() {
        return recipient;
    }

    public void setRecipient(User recipient) {
        this.recipient = recipient;
        if (recipient != null) {
            this.email = recipient.getEmail();
        }
    }

    public User getActor() {
        return actor;
    }

    public void setActor(User actor) {
        this.actor = actor;
    }

    public void setEmail(
            String email
    ) {
        this.email = email;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(
            String message
    ) {
        this.message = message;
    }

    public boolean isRead() {
        return isRead;
    }

    public void setRead(
            boolean read
    ) {
        isRead = read;
    }

    public String getType() {
        return type;
    }

    public void setType(
            String type
    ) {
        this.type = type;
    }

    public String getLinkUrl() {
        return linkUrl;
    }

    public void setLinkUrl(
            String linkUrl
    ) {
        this.linkUrl = linkUrl;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(
            LocalDateTime timestamp
    ) {
        this.timestamp = timestamp;
    }
}
