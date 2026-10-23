package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.InboxAssistantResponse;
import com.alumni.alumni_connect.service.InboxAssistantService;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class InboxAssistantController {
    private final InboxAssistantService service;

    public InboxAssistantController(InboxAssistantService service) { this.service = service; }

    @PostMapping("/messages/{messageId}/assistant")
    @PreAuthorize("hasRole('ALUMNI')")
    public InboxAssistantResponse analyze(@PathVariable @Positive long messageId) {
        return service.analyzeIncomingMessage(messageId);
    }
}
