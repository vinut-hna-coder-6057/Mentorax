package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.knowledge.*;
import com.alumni.alumni_connect.service.KnowledgeConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/companion/conversations")
@PreAuthorize("hasRole('STUDENT')")
public class KnowledgeConversationController {
    private final KnowledgeConversationService service;
    public KnowledgeConversationController(KnowledgeConversationService service) { this.service = service; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanionConversationResponse create(@Valid @RequestBody(required = false) ConversationCreateRequest request) {
        return service.create(request);
    }

    @GetMapping
    public List<CompanionConversationResponse> list(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}/messages")
    public List<CompanionMessageResponse> messages(@PathVariable long id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.messages(id, page, size);
    }

    @PostMapping("/{id}/messages")
    public List<CompanionMessageResponse> ask(@PathVariable long id,
            @Valid @RequestBody ConversationQuestionRequest request) {
        return service.ask(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id); }
}
