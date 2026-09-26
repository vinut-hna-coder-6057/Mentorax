package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository repository;

    private final SimpMessagingTemplate messagingTemplate;

        private final UserRepository userRepository;

    public NotificationService(

            NotificationRepository repository,
            SimpMessagingTemplate messagingTemplate,

            UserRepository userRepository

    ) {

        this.repository =
                repository;

        this.messagingTemplate =
                messagingTemplate;

        this.userRepository = userRepository;
    }

    // =====================================
    // CREATE NOTIFICATION
    // =====================================

    public void sendNotification(

            String email,

            String message,

            String type,

            String linkUrl

    ) {

        User recipient = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Notification recipient not found"));
        Notification notification = new Notification();
        notification.setRecipient(recipient); // dual-writes legacy email through the entity setter

        notification.setMessage(message);

        notification.setType(type);

        notification.setLinkUrl(linkUrl);

        notification.setTimestamp(
                LocalDateTime.now()
        );

        // SAVE DATABASE

        Notification saved =
                repository.save(notification);

        // REALTIME WEBSOCKET

        messagingTemplate.convertAndSendToUser(recipient.getEmail(), "/queue/notifications", saved);
    }

    // =====================================
    // GET NOTIFICATIONS
    // =====================================

    public List<Notification> getNotifications(
            String email
    ) {

        return repository.findByRecipient_IdOrderByTimestampDesc(requireRecipient(email).getId());
    }

    // =====================================
    // UNREAD COUNT
    // =====================================

    public long getUnreadCount(
            String email
    ) {

        return repository.countByRecipient_IdAndIsReadFalse(requireRecipient(email).getId());
    }

    // =====================================
    // MARK READ
    // =====================================

    public Notification markRead(

            Long id,

            String email

    ) {

        Notification notification =
                repository.findById(id)
                        .orElseThrow(() ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "Notification not found"
                                )
                        );

        // =====================================
        // CHECK OWNERSHIP
        // =====================================

        User recipient = requireRecipient(email);
        if (notification.getRecipient() == null || !recipient.getId().equals(notification.getRecipient().getId())) {

            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You cannot modify this notification"
            );
        }

        // =====================================
        // MARK AS READ
        // =====================================

        notification.setRead(true);

        return repository.save(
                notification
        );
    }

    private User requireRecipient(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user no longer exists"));
    }
}

