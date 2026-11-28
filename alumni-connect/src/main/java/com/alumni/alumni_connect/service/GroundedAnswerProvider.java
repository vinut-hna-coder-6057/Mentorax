package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;

import java.util.List;

/** Generates an answer using only the bounded, currently eligible evidence supplied by Spring. */
public interface GroundedAnswerProvider {
    String id();

    /** Null means configured; otherwise this provider must not be called. */
    default String configurationError() { return null; }

    default boolean isConfigured() { return configurationError() == null; }

    default boolean supportsGeneralGuidance() { return false; }

    default String generateGeneralGuidance(String question) {
        throw new UnsupportedOperationException("General guidance is not supported by this provider");
    }

    String generate(String question, List<KnowledgeRetrievalResponse.Source> sources, boolean conflicting);
}
