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
@Table(name = "messages")

public class Message {

    @Id

    @GeneratedValue(strategy = GenerationType.IDENTITY)

    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Conversation conversation;

    // Request/response convenience field. The persisted relationship above is
    // authoritative; this lets STOMP clients name an existing conversation.
    @Transient
    private Long conversationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id")
    @com.fasterxml.jackson.annotation.JsonIgnore
    private User sender;

    private String senderEmail;

    private String receiverEmail;

    @Column(length = 2000)

    private String content;

    private LocalDateTime timestamp;

    public Message() {}

    public Message(

            String senderEmail,

            String receiverEmail,

            String content,

            LocalDateTime timestamp

    ) {

        this.senderEmail = senderEmail;

        this.receiverEmail = receiverEmail;

        this.content = content;

        this.timestamp = timestamp;
    }

    // ID

    public Long getId() {
        return id;
    }

    public Conversation getConversation() { return conversation; }
    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
        this.conversationId = conversation == null ? null : conversation.getId();
    }
    public Long getConversationId() {
        return conversation == null ? conversationId : conversation.getId();
    }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public User getSender() { return sender; }
    public void setSender(User sender) { this.sender = sender; }

    // SENDER

    public String getSenderEmail() {
        return senderEmail;
    }

    public void setSenderEmail(String senderEmail) {
        this.senderEmail = senderEmail;
    }

    // RECEIVER

    public String getReceiverEmail() {
        return receiverEmail;
    }

    public void setReceiverEmail(String receiverEmail) {
        this.receiverEmail = receiverEmail;
    }

    // CONTENT

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    // TIMESTAMP

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}

