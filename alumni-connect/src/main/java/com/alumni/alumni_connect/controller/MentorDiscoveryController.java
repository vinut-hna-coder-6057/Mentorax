package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.MentorDiscoveryRequest;
import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.service.CareerAssistantService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/ai/mentor-discovery", "/ai/career-assistant"})
public class MentorDiscoveryController {
    private final CareerAssistantService service;
    public MentorDiscoveryController(CareerAssistantService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public MentorDiscoveryResponse discover(@Valid @RequestBody MentorDiscoveryRequest request) {
        return service.respond(request.goal().trim());
    }
}
