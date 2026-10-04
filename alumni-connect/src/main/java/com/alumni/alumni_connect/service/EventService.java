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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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
    public Event createEvent(EventRequest request, String authenticatedEmail) {
    User creator = userRepository.findByEmail(authenticatedEmail)
            .orElseThrow(() ->
                    new IllegalArgumentException("Authenticated user not found"));

    boolean admin = "ADMIN".equalsIgnoreCase(creator.getRole());
    boolean alumni = "ALUMNI".equalsIgnoreCase(creator.getRole());
    if (!admin && !alumni) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Only alumni and admins can create events");
    }

    Event event = new Event();

    event.setTitle(request.title());
    event.setDescription(request.description());
    event.setLocation(request.location());
    event.setEventDate(request.eventDate());
    event.setCategory(request.category());
    event.setMeetingLink(request.meetingLink());
    event.setImageUrl(request.imageUrl());

    event.setCreator(creator);
    event.setRole(creator.getRole());
    event.setCreatedAt(LocalDateTime.now());

    if (admin) {
        event.setStatus("APPROVED");
    } else {
        event.setStatus("PENDING");
    }

    event.setAttendeeCount(0);

    return eventRepository.save(event);
}
    @Transactional
public Event updateEvent(
        Long id,
        EventRequest request,
        String authenticatedEmail
) {
    Event event = eventRepository.findById(id)
            .orElseThrow(() ->
                    new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Event not found"
                    ));

    requireCreatorOrAdmin(event, authenticatedEmail);

    event.setTitle(request.title());
    event.setDescription(request.description());
    event.setLocation(request.location());
    event.setEventDate(request.eventDate());
    event.setCategory(request.category());
    event.setMeetingLink(request.meetingLink());
    event.setImageUrl(request.imageUrl());

    // Never copy privileged fields from client data.
    return eventRepository.save(event);
}
    @Transactional
    public void deleteEvent(Long id, String authenticatedEmail) {
        Event event = eventRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        requireCreatorOrAdmin(event, authenticatedEmail);
        if (registrationRepository.countByEventId(id) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot delete an event with registrations");
        }
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
    public List<Event> getApprovedEvents(int page, int size) { return eventRepository.findByStatusOrderByCreatedAtDesc("APPROVED", bounded(page,size)); }

    public List<Event> getVisibleEvents(int page, int size, String authenticatedEmail) {
        if (authenticatedEmail == null) {
            return getApprovedEvents(page, size);
        }
        User caller = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));
        if ("ADMIN".equalsIgnoreCase(caller.getRole())) {
            return getApprovedEvents(page, size);
        }
        return eventRepository.findApprovedOrCreatedBy(caller.getId(), bounded(page, size));
    }

    // =====================================
    // GET ALL EVENTS
    // =====================================

    public List<Event> getAllEvents() {

        return eventRepository
                .findAllByOrderByCreatedAtDesc();
    }
    public List<Event> getAllEvents(int page, int size) { return eventRepository.findAllByOrderByCreatedAtDesc(bounded(page,size)); }

    // =====================================
    // APPROVE EVENT
    // =====================================

    @Transactional
    public Event approveEvent(Long id) {

        Event event =
                eventRepository.findByIdForUpdate(id)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                        );

        requirePending(event);
        event.setStatus("APPROVED");

        return eventRepository.save(event);
    }

    // =====================================
    // REJECT EVENT
    // =====================================

    @Transactional
    public Event rejectEvent(Long id) {

        Event event =
                eventRepository.findByIdForUpdate(id)
                        .orElseThrow(() ->
                                new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
                        );

        requirePending(event);
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

        if (!"APPROVED".equalsIgnoreCase(event.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Event is not accepting registrations");
        }

        User user = userRepository.findByEmail(studentEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user not found"));

        // =====================================
        // CHECK DUPLICATE
        // =====================================

        boolean alreadyRegistered =
                registrationRepository
                        .existsByEventIdAndUser_Id(
                                eventId,
                                user.getId()
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

        registration.setEventId(eventId);

        registration.setEvent(event);

        registration.setUser(user);

        // setUser maintains the legacy email column while user_id is authoritative.

        registration.setRegisteredAt(
                LocalDateTime.now()
        );

        try {

            registrationRepository.saveAndFlush(
                    registration
            );

        } catch (DataIntegrityViolationException e) {

            // DATABASE UNIQUE CONSTRAINT
            // PROTECTS AGAINST RACE CONDITIONS

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Already registered"
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
    public ResponseEntity<?> getRegistrationStatus(
        Long eventId,
        String authenticatedEmail
) {
    // Make sure the event exists.
    if (!eventRepository.existsById(eventId)) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Event not found"
        );
    }

    User user = userRepository.findByEmail(authenticatedEmail)
            .orElseThrow(() -> new IllegalArgumentException(
                    "Authenticated user not found"
            ));

    boolean registered = registrationRepository
            .findByEventIdAndUser_Id(eventId, user.getId())
            .isPresent();

    return ResponseEntity.ok(
            Map.of(
                    "registered",
                    registered
            )
    );
}
    public List<EventRegistration> getAttendees(Long eventId, String authenticatedEmail) {
        return getAttendees(eventId,authenticatedEmail,0,50);
    }
    public List<EventRegistration> getAttendees(Long eventId,String authenticatedEmail,int page,int size) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
        requireCreatorOrAdmin(event, authenticatedEmail);
        return registrationRepository.findByEventId(eventId,PageRequest.of(Math.max(0,page),Math.max(1,Math.min(size,100)),Sort.by("registeredAt").descending()));
    }

    private Pageable bounded(int page,int size) { return PageRequest.of(Math.max(0,page),Math.max(1,Math.min(size,100)), Sort.by("createdAt").descending()); }

    private void requireCreatorOrAdmin(Event event, String authenticatedEmail) {
        User caller = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated user not found"));
        boolean owner = event.getCreator() != null && caller.getId().equals(event.getCreator().getId());
        boolean admin = "ADMIN".equalsIgnoreCase(caller.getRole());
        if (!owner && !admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not control this event");
    }

    private void requirePending(Event event) {
        if (!"PENDING".equalsIgnoreCase(event.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only pending events can be approved or rejected");
        }
    }
}
