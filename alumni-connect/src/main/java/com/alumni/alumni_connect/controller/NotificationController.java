package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController

public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(
            NotificationService notificationService
    ) {

        this.notificationService =
                notificationService;
    }

    // =====================================
    // GET ALL NOTIFICATIONS
    // =====================================

    @GetMapping(
            "/notifications"
    )

    public List<Notification> getNotifications() {

        String email =
                getAuthenticatedEmail();

        return notificationService
                .getNotifications(email);
    }

    // =====================================
    // UNREAD COUNT
    // =====================================

    @GetMapping(
            "/notifications/unread"
    )

    public long getUnreadCount() {

        String email =
                getAuthenticatedEmail();

        return notificationService
                .getUnreadCount(email);
    }

    // =====================================
    // MARK READ
    // =====================================

    @PutMapping(
            "/notifications/read/{id}"
    )

    public Notification markRead(

            @PathVariable Long id

    ) {

        String email =
                getAuthenticatedEmail();

        return notificationService
                .markRead(id, email);
    }

    // =====================================
    // GET LOGGED-IN USER EMAIL
    // =====================================

    private String getAuthenticatedEmail() {

        if (
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        == null
        ) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required"
            );
        }

        return SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();
    }
}
