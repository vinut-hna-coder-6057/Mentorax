package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

@Service
public class MessageService {

    private final SimpMessagingTemplate messagingTemplate;

    private final MessageRepository repository;

    private final NotificationService notificationService;

    private final UserRepository userRepository;

        private final ConversationRepository conversationRepository;

        private final ConversationParticipantRepository participantRepository;

    public MessageService(
            SimpMessagingTemplate messagingTemplate,
            MessageRepository repository,
            NotificationService notificationService,
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository
    ) {

        this.messagingTemplate =
                messagingTemplate;

        this.repository =
                repository;

        this.notificationService =
                notificationService;

        this.userRepository =
                userRepository;

        this.conversationRepository = conversationRepository;

        this.participantRepository = participantRepository;
    }

    // =====================================
    // SEND MESSAGE
    // =====================================

    @Transactional
    public void sendMessage(Message message, String authenticatedEmail) {

        // GET REAL LOGGED-IN USER FROM JWT

        // CHECK RECEIVER

        if (message.getReceiverEmail() == null
                || message.getReceiverEmail().isBlank()) {

            throw new IllegalArgumentException("Receiver is required");
        }

        // CHECK RECEIVER EXISTS

        if (!userRepository.findByEmail(message.getReceiverEmail()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiver not found");
        }

        // NEVER TRUST SENDER FROM FRONTEND
        User sender = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Authenticated user no longer exists"));
        User receiver = userRepository.findByEmail(message.getReceiverEmail())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Receiver not found"));

        message.setSenderEmail(authenticatedEmail);
        message.setSender(sender);

        Conversation conversation;
        if (message.getConversationId() != null) {
            conversation = conversationRepository.findById(message.getConversationId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Conversation not found"));
        } else {
            conversation = getOrCreateDirectConversation(sender, receiver);
        }
        if (!participantRepository.existsByConversation_IdAndUser_Id(conversation.getId(), sender.getId())
                || !participantRepository.existsByConversation_IdAndUser_Id(conversation.getId(), receiver.getId())) {
            throw new IllegalArgumentException("Conversation membership is invalid");
        }
        message.setConversation(conversation);

        // CHECK CONTENT

        if (message.getContent() == null
                || message.getContent().isBlank()) {

            throw new IllegalArgumentException("Message content is required");
        }

        if (message.getContent().length() > 2000) {

            throw new IllegalArgumentException("Message content is too long");
        }

        // SERVER CONTROLS TIMESTAMP

        message.setTimestamp(
                LocalDateTime.now()
        );

        // SAVE MESSAGE

        Message saved =
                repository.save(message);

        // SEND TO RECEIVER

        messagingTemplate.convertAndSendToUser(saved.getReceiverEmail(), "/queue/messages", saved);

        // SEND BACK TO SENDER

        messagingTemplate.convertAndSendToUser(saved.getSenderEmail(), "/queue/messages", saved);

        // SEND NOTIFICATION

        notificationService.sendNotification(

                saved.getReceiverEmail(),

                "New message from "
                        + saved.getSenderEmail(),

                "MESSAGE",

                "/chat/"
                        + saved.getSenderEmail()
        );

        conversation.setLastMessageAt(saved.getTimestamp());
        conversationRepository.save(conversation);
    }

    private Conversation getOrCreateDirectConversation(User first, User second) {
        if (first.getId().equals(second.getId())) {
            throw new IllegalArgumentException("A direct conversation requires two different users");
        }

        long lowId = Math.min(first.getId(), second.getId());
        long highId = Math.max(first.getId(), second.getId());
        conversationRepository.createDirectConversationIfAbsent(lowId, highId);
        Conversation conversation = conversationRepository
                .findDirectConversation(lowId, highId)
                .orElseThrow(() -> new IllegalStateException("Direct conversation was not created"));

        participantRepository.addParticipantIfAbsent(conversation.getId(), first.getId());
        participantRepository.addParticipantIfAbsent(conversation.getId(), second.getId());
        return conversation;
    }

    // =====================================
    // GET CONVERSATION
    // =====================================
    

}