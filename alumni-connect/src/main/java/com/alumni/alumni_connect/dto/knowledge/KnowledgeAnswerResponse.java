package com.alumni.alumni_connect.dto.knowledge;

import java.util.List;

public record KnowledgeAnswerResponse(String answer, String evidenceStatus, String answerMode,
                                      String followUpReason, List<KnowledgeRetrievalResponse.Source> sources,
                                      List<Mentor> mentors) {
    public record Mentor(long id, String displayName, String jobTitle, String company,
                         double relevance, String uncertainty, String conversationTopic) {}
}
