package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.knowledge.*;
import com.alumni.alumni_connect.service.GroundedKnowledgeAnswerService;
import com.alumni.alumni_connect.service.KnowledgeRetrievalService;
import com.alumni.alumni_connect.service.UnansweredQuestionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class KnowledgeRetrievalController {
    private final KnowledgeRetrievalService retrieval;
    private final GroundedKnowledgeAnswerService answers;
    private final UnansweredQuestionService unanswered;

    public KnowledgeRetrievalController(KnowledgeRetrievalService retrieval, GroundedKnowledgeAnswerService answers,
                                        UnansweredQuestionService unanswered) {
        this.retrieval = retrieval;
        this.answers = answers;
        this.unanswered = unanswered;
    }

    @GetMapping("/api/knowledge/retrieval")
    @PreAuthorize("hasRole('STUDENT')")
    public KnowledgeRetrievalResponse retrieve(@RequestParam String query,
            @RequestParam(required = false) KnowledgeContributionRequest.Category category,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return retrieval.retrieve(query, category, company, role, skill, topic, page, size);
    }

    @PostMapping("/api/knowledge/companion/answer")
    @PreAuthorize("hasRole('STUDENT')")
    public KnowledgeAnswerResponse answer(@Valid @RequestBody KnowledgeQuestionRequest request) {
        return answers.answer(request.question().trim());
    }

    @GetMapping("/api/alumni/knowledge/opportunities")
    @PreAuthorize("hasRole('ALUMNI')")
    public java.util.List<KnowledgeOpportunityResponse> opportunities() {
        return unanswered.opportunities();
    }
}
