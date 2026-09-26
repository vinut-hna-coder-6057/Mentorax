package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class EventRegistrationService {

    private final EventRepository eventRepository;

    private final EventRegistrationRepository registrationRepository;

    private final EmailService emailService;

    public EventRegistrationService(
            EventRepository eventRepository,
            EventRegistrationRepository registrationRepository,
            EmailService emailService
    ) {
        this.eventRepository = eventRepository;
        this.registrationRepository = registrationRepository;
        this.emailService = emailService;
    }

    // =====================================
    // REGISTER FOR EVENT
    // =====================================

    @Transactional
    public String registerForEvent(
            Long eventId,
            String studentEmail
    ) {

        // =====================================
        // LOCK EVENT
        // =====================================

        Event event = eventRepository
                .findByIdForUpdate(eventId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                );

        // =====================================
        // CHECK DUPLICATE
        // =====================================

        boolean alreadyRegistered =
                registrationRepository
                        .existsByEventIdAndStudentEmail(
                                eventId,
                                studentEmail
                        );

        if (alreadyRegistered) {
           throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Already registered"
);
        }

        // =====================================
        // CREATE REGISTRATION
        // =====================================

        EventRegistration registration =
                new EventRegistration();

        registration.setEventId(eventId);

        registration.setStudentEmail(
                studentEmail
        );

        registration.setRegisteredAt(
                LocalDateTime.now()
        );

        registrationRepository.save(
                registration
        );

        // =====================================
        // UPDATE RSVP COUNT
        // =====================================

        event.setAttendeeCount(
                event.getAttendeeCount() + 1
        );

        eventRepository.save(event);

        // =====================================
        // SEND EMAIL
        // =====================================

        emailService.sendEventRegistrationEmail(
                studentEmail,
                event.getTitle(),
                event.getEventDate(),
                event.getLocation(),
                event.getMeetingLink()
        );

        return "Registered successfully";
    }

    // =====================================
    // CANCEL REGISTRATION
    // =====================================

    @Transactional
    public String cancelRegistration(
            Long eventId,
            String studentEmail
    ) {

        // LOCK EVENT

        Event event = eventRepository
                .findByIdForUpdate(eventId)
                .orElseThrow(() ->
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                );

        // CHECK REGISTRATION

        boolean registered =
                registrationRepository
                        .existsByEventIdAndStudentEmail(
                                eventId,
                                studentEmail
                        );

        if (!registered) {
           throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        "Not registered"
);
        }

        // DELETE REGISTRATION

        registrationRepository
                .deleteByEventIdAndStudentEmail(
                        eventId,
                        studentEmail
                );

        // DECREASE COUNT

        if (event.getAttendeeCount() > 0) {

            event.setAttendeeCount(
                    event.getAttendeeCount() - 1
            );

            eventRepository.save(event);
        }

        return "Registration cancelled";
    }
}
