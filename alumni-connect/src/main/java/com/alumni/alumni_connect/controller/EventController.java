package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

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
    public Event createEvent(
            @RequestBody Event event, Authentication authentication
    ) {

        return eventService.createEvent(event, authentication.getName());
    }

    // =====================================
    // STUDENT EVENTS
    // ONLY APPROVED EVENTS
    // =====================================

    @GetMapping("/events")
    public List<Event> getApprovedEvents() {

        return eventService.getApprovedEvents();
    }

    // =====================================
    // ADMIN EVENT PANEL
    // VIEW ALL EVENTS
    // =====================================

    @GetMapping("/events/all")
    public List<Event> getAllEvents() {

        return eventService.getAllEvents();
    }

    // =====================================
    // APPROVE EVENT
    // =====================================

    @PutMapping("/events/approve/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Event approveEvent(
            @PathVariable Long id
    ) {

        return eventService.approveEvent(id);
    }

    // =====================================
    // REJECT EVENT
    // =====================================

    @PutMapping("/events/reject/{id}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public Event rejectEvent(
            @PathVariable Long id
    ) {

        return eventService.rejectEvent(id);
    }

    @PutMapping("/events/{id}")
    public Event updateEvent(@PathVariable Long id, @RequestBody Event event, Authentication authentication) {
        return eventService.updateEvent(id, event, authentication.getName());
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

    // =====================================
    // EVENT ATTENDEES
    // =====================================

    @GetMapping("/events/attendees/{eventId}")
    public List<EventRegistration> getAttendees(

            @PathVariable Long eventId, Authentication authentication

    ) {

        return eventService.getAttendees(eventId, authentication.getName());
    }
}
