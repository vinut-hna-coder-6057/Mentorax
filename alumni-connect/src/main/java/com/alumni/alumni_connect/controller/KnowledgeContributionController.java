package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.knowledge.*;
import com.alumni.alumni_connect.service.KnowledgeContributionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class KnowledgeContributionController {
    private final KnowledgeContributionService service;

    public KnowledgeContributionController(KnowledgeContributionService service) { this.service = service; }

    @PostMapping("/api/alumni/knowledge")
    @PreAuthorize("hasRole('ALUMNI')")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeContributionResponse create(@Valid @RequestBody KnowledgeContributionRequest request) {
        return service.create(request);
    }

    @GetMapping("/api/alumni/knowledge/mine")
    @PreAuthorize("hasRole('ALUMNI')")
    public List<KnowledgeContributionResponse> mine(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return service.listMine(page, size);
    }

    @GetMapping("/api/alumni/knowledge/{id}")
    @PreAuthorize("hasRole('ALUMNI')")
    public KnowledgeContributionResponse getMine(@PathVariable long id) { return service.getMine(id); }

    @GetMapping("/api/alumni/knowledge/{id}/versions")
    @PreAuthorize("hasRole('ALUMNI')")
    public List<KnowledgeContributionResponse> versions(@PathVariable long id) { return service.listVersions(id); }

    @PutMapping("/api/alumni/knowledge/{id}")
    @PreAuthorize("hasRole('ALUMNI')")
    public KnowledgeContributionResponse update(@PathVariable long id,
                                                 @Valid @RequestBody KnowledgeContributionRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/api/alumni/knowledge/{id}/publish")
    @PreAuthorize("hasRole('ALUMNI')")
    public KnowledgeContributionResponse publish(@PathVariable long id,
                                                   @Valid @RequestBody PublishConsentRequest request) {
        return service.publish(id, request);
    }

    @PostMapping("/api/alumni/knowledge/{id}/withdraw")
    @PreAuthorize("hasRole('ALUMNI')")
    public KnowledgeContributionResponse withdraw(@PathVariable long id) { return service.withdraw(id); }

    @PatchMapping("/api/alumni/knowledge/{id}/consent")
    @PreAuthorize("hasRole('ALUMNI')")
    public KnowledgeContributionResponse consent(@PathVariable long id,
                                                   @Valid @RequestBody ConsentUpdateRequest request) {
        return service.updateConsent(id, request);
    }

    @GetMapping("/api/knowledge/contributions")
    @PreAuthorize("hasRole('STUDENT')")
    public List<KnowledgeContributionResponse> visible(@RequestParam(required = false) KnowledgeContributionRequest.Category category,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return service.listStudentVisible(category, page, size);
    }
}
