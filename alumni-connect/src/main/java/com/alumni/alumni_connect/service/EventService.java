package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class EventService {

    private final EventRepository eventRepository;

    private final EventRegistrationRepository registrationRepository;

    private final EmailService emailService;

        private final UserRepository userRepository;

    public EventService(
            EventRepository eventRepository,
            EventRegistrationRepository registrationRepository,
            EmailService emailService,
            UserRepository userRepository
    ) {

        this.eventRepository = eventRepository;

        this.registrationRepository = registrationRepository;

        this.emailService = emailService;

        this.userRepository = userRepository;
    }

    // =====================================
    // CREATE EVENT
    // =====================================

    public Event createEvent(Event event, String authenticatedEmail) {
        User creator = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new IllegalArgumentException("Authenticated user not found"));
        event.setCreator(creator);
        event.setRole(creator.getRole());

        event.setCreatedAt(
                LocalDateTime.now()
        );

        // ADMIN EVENTS AUTO APPROVED

        if (
                creator.getRole() != null
                        &&
                creator.getRole()
                        .toUpperCase()
                        .contains("ADMIN")
        ) {

            event.setStatus("APPROVED");

        } else {

            event.setStatus("PENDING");
        }

        event.setAttendeeCount(0);

        return eventRepository.save(event);
    }

    @Transactional
    public Event updateEvent(Long id, Event request, String authenticatedEmail) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        requireCreatorOrAdmin(event, authenticatedEmail);
        event.setTitle(request.getTitle());
        event.setDescription(request.getDescription());
        event.setLocation(request.getLocation());
        event.setEventDate(request.getEventDate());
        event.setCategory(request.getCategory());
        event.setMeetingLink(request.getMeetingLink());
        event.setImageUrl(request.getImageUrl());
        // Never copy createdBy, creator, role, status, or attendeeCount from client data.
        return eventRepository.save(event);
    }

    @Transactional
    public void deleteEvent(Long id, String authenticatedEmail) {
        Event event = eventRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        requireCreatorOrAdmin(event, authenticatedEmail);
        eventRepository.delete(event);
    }

    // =====================================
    // GET APPROVED EVENTS
    // =====================================

    public List<Event> getApprovedEvents() {

        return eventRepository
                .findByStatusOrderByCreatedAtDesc(
                        "APPROVED"
                );
    }

    // =====================================
    // GET ALL EVENTS
    // =====================================

    public List<Event> getAllEvents() {

        return eventRepository
                .findAllByOrderByCreatedAtDesc();
    }

    // =====================================
    // APPROVE EVENT
    // =====================================

    public Event approveEvent(Long id) {

        Event event =
                eventRepository.findById(id)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                        );

        event.setStatus("APPROVED");

        return eventRepository.save(event);
    }

    // =====================================
    // REJECT EVENT
    // =====================================

    public Event rejectEvent(Long id) {

        Event event =
                eventRepository.findById(id)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                        );

        event.setStatus("REJECTED");

        return eventRepository.save(event);
    }

    // =====================================
    // REGISTER FOR EVENT
    // =====================================

    @Transactional
    public ResponseEntity<?> registerForEvent(
            Long eventId,
            String studentEmail
    ) {

        // =====================================
        // GET + LOCK EVENT
        // =====================================

        Event event =
                eventRepository.findByIdForUpdate(eventId)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                        );

        // =====================================
        // CHECK DUPLICATE
        // =====================================

        boolean alreadyRegistered =
                registrationRepository
                        .existsByEventIdAndUser_Id(
                                eventId,
                                userRepository.findByEmail(studentEmail).orElseThrow(() -> new IllegalArgumentException("Authenticated user not found")).getId()
                        );

        if (alreadyRegistered) {

            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Already registered"
                    )
            );
        }

        // =====================================
        // CREATE REGISTRATION
        // =====================================

        EventRegistration registration =
                new EventRegistration();

        User user = userRepository.findByEmail(studentEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        registration.setEventId(eventId);

        registration.setEvent(event);

        registration.setUser(user);

        // setUser maintains the legacy email column while user_id is authoritative.

        registration.setRegisteredAt(
                LocalDateTime.now()
        );

        try {

            registrationRepository.save(
                    registration
            );

        } catch (DataIntegrityViolationException e) {

            // DATABASE UNIQUE CONSTRAINT
            // PROTECTS AGAINST RACE CONDITIONS

            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Already registered"
                    )
            );
        }

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

        return ResponseEntity.ok(
                Map.of(
                        "message",
                        "Registered successfully"
                )
        );
    }

    // =====================================
    // CANCEL REGISTRATION
    // =====================================

    @Transactional
    public ResponseEntity<?> cancelRegistration(
            Long eventId,
            String studentEmail
    ) {

        // =====================================
        // LOCK EVENT
        // =====================================

        Event event =
                eventRepository.findByIdForUpdate(eventId)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                        );

        // =====================================
        // FIND REGISTRATION
        // =====================================

        Optional<EventRegistration> registration =
                registrationRepository
                        .findByEventIdAndUser_Id(
                                eventId,
                                userRepository.findByEmail(studentEmail).orElseThrow(() -> new IllegalArgumentException("Authenticated user not found")).getId()
                        );

        // =====================================
        // NOT REGISTERED
        // =====================================

        if (registration.isEmpty()) {

            return ResponseEntity.ok(
                    Map.of(
                            "message",
                            "Not registered"
                    )
            );
        }

        // =====================================
        // DELETE REGISTRATION
        // =====================================

        registrationRepository.delete(
                registration.get()
        );

        // =====================================
        // DECREASE COUNT
        // =====================================

        if (event.getAttendeeCount() > 0) {

            event.setAttendeeCount(
                    event.getAttendeeCount() - 1
            );

            eventRepository.save(event);
        }

        return ResponseEntity.ok(
                Map.of(
                        "message",
                        "Registration cancelled"
                )
        );
    }

    // =====================================
    // EVENT ATTENDEES
    // =====================================

    public List<EventRegistration> getAttendees(Long eventId, String authenticatedEmail) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        requireCreatorOrAdmin(event, authenticatedEmail);
        return registrationRepository.findByEventId(eventId);
    }

    private void requireCreatorOrAdmin(Event event, String authenticatedEmail) {
        User caller = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
        boolean owner = event.getCreator() != null && caller.getId().equals(event.getCreator().getId());
        boolean admin = "ADMIN".equalsIgnoreCase(caller.getRole());
        if (!owner && !admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not control this event");
    }
}
