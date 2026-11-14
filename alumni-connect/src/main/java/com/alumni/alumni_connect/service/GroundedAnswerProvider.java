package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;

import java.util.List;

/** Generates an answer using only the bounded, currently eligible evidence supplied by Spring. */
public interface GroundedAnswerProvider {
    String id();

    String generate(String question, List<KnowledgeRetrievalResponse.Source> sources, boolean conflicting);
}
