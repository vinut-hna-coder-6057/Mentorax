package com.alumni.alumni_connect.dto;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import java.time.LocalDateTime;

public class ConversationDTO {

    private String email;

    private String displayName;

    private String profileImage;

    private String participantRole;

    private String latestMessage;

    private LocalDateTime timestamp;

    public ConversationDTO() {}

    public ConversationDTO(

            String email,

            String latestMessage,

            LocalDateTime timestamp

    ) {
        this(email, null, null, null, latestMessage, timestamp);
    }

    public ConversationDTO(

            String email,

            String displayName,

            String profileImage,

            String participantRole,

            String latestMessage,

            LocalDateTime timestamp

    ) {

        this.email = email;

        this.displayName = displayName;

        this.profileImage = profileImage;

        this.participantRole = participantRole;

        this.latestMessage = latestMessage;

        this.timestamp = timestamp;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProfileImage() {
        return profileImage;
    }

    public void setProfileImage(String profileImage) {
        this.profileImage = profileImage;
    }

    public String getParticipantRole() {
        return participantRole;
    }

    public void setParticipantRole(String participantRole) {
        this.participantRole = participantRole;
    }

    public String getLatestMessage() {
        return latestMessage;
    }

    public void setLatestMessage(
            String latestMessage
    ) {
        this.latestMessage = latestMessage;
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
