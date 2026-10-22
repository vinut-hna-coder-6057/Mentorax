package com.alumni.alumni_connect.service;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeneralQuestionAnswerService {
    public AnswerResult answer(String question) {
        return new AnswerResult(null,
                "General factual answering is not configured yet. No alumni were searched.",
                List.of());
    }

    public record AnswerResult(String answer, String message, List<String> sources) {}
}
