package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.MentorDiscoveryRequest;
import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.service.MentorDiscoveryService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/ai/mentor-discovery")
public class MentorDiscoveryController {
    private final MentorDiscoveryService service;
    public MentorDiscoveryController(MentorDiscoveryService service) { this.service = service; }

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public MentorDiscoveryResponse discover(@Valid @RequestBody MentorDiscoveryRequest request) {
        return service.discover(request.goal().trim());
    }
}
