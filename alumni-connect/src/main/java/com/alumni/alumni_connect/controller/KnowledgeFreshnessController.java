package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.knowledge.FreshnessSettingsRequest;
import com.alumni.alumni_connect.dto.knowledge.FreshnessStatusResponse;
import com.alumni.alumni_connect.service.KnowledgeFreshnessService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/alumni/knowledge/{id}/freshness")
@PreAuthorize("hasRole('ALUMNI')")
public class KnowledgeFreshnessController {
    private final KnowledgeFreshnessService service;
    public KnowledgeFreshnessController(KnowledgeFreshnessService service) { this.service = service; }
    @GetMapping public FreshnessStatusResponse get(@PathVariable long id) { return service.get(id); }
    @PatchMapping public FreshnessStatusResponse configure(@PathVariable long id,
            @Valid @RequestBody FreshnessSettingsRequest request) { return service.configure(id, request); }
    @PostMapping("/confirm") public FreshnessStatusResponse confirm(@PathVariable long id) { return service.confirm(id); }
}
