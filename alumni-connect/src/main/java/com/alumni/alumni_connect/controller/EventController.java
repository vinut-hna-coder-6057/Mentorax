package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.core.Authentication;

@RestController
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    // =====================================
    // CREATE EVENT
    // =====================================
  @PostMapping("/events")
public EventDTO createEvent(
        @Valid @RequestBody EventRequest request,
        Authentication authentication
) {
    return EventDTO.from(
            eventService.createEvent(request, authentication.getName())
    );
}

    // =====================================
    // STUDENT EVENTS
    // ONLY APPROVED EVENTS
    // =====================================

    @GetMapping("/events")
    public List<EventDTO> getApprovedEvents(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return eventService.getApprovedEvents(page,size).stream().map(EventDTO::from).toList();
    }

    // =====================================
    // ADMIN EVENT PANEL
    // VIEW ALL EVENTS
    // =====================================

    @GetMapping("/events/all")
    public List<EventDTO> getAllEvents(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return eventService.getAllEvents(page,size).stream().map(EventDTO::from).toList();
    }

    // =====================================
    // APPROVE EVENT
    // =====================================

    @PutMapping("/events/approve/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public EventDTO approveEvent(
            @PathVariable Long id
    ) {

        return EventDTO.from(eventService.approveEvent(id));
    }

    // =====================================
    // REJECT EVENT
    // =====================================

    @PutMapping("/events/reject/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public EventDTO rejectEvent(
            @PathVariable Long id
    ) {

        return EventDTO.from(eventService.rejectEvent(id));
    }
    @PutMapping("/events/{id}")
public EventDTO updateEvent(
        @PathVariable Long id,
        @Valid @RequestBody EventRequest request,
        Authentication authentication
) {
    return EventDTO.from(
            eventService.updateEvent(id, request, authentication.getName())
    );
}

    @DeleteMapping("/events/{id}")
    public void deleteEvent(@PathVariable Long id, Authentication authentication) {
        eventService.deleteEvent(id, authentication.getName());
    }

    // =====================================
    // REGISTER FOR EVENT
    // =====================================

    @PostMapping("/events/register")
    public ResponseEntity<?> registerForEvent(

            @RequestParam Long eventId,
            @RequestParam(required = false) String studentEmail,
            Authentication authentication

    ) {

        return eventService.registerForEvent(
                eventId,
                authentication.getName()
        );
    }

    // =====================================
    // CANCEL REGISTRATION
    // =====================================

    @DeleteMapping("/events/register")
    public ResponseEntity<?> cancelRegistration(

            @RequestParam Long eventId,

            @RequestParam(required = false) String studentEmail,
            Authentication authentication

    ) {

        return eventService.cancelRegistration(
                eventId,
                authentication.getName()
        );
    }
    @GetMapping("/events/{eventId}/registration-status")
public ResponseEntity<?> getRegistrationStatus(
        @PathVariable Long eventId,
        Authentication authentication
) {
    return eventService.getRegistrationStatus(
            eventId,
            authentication.getName()
    );
}

    // =====================================
    // EVENT ATTENDEES
    // =====================================

    @GetMapping("/events/attendees/{eventId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<EventRegistrationResponse> getAttendees(

            @PathVariable Long eventId, Authentication authentication,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size

    ) {

        return eventService.getAttendees(eventId, authentication.getName(),page,size).stream().map(EventRegistrationResponse::from).toList();
    }
}
