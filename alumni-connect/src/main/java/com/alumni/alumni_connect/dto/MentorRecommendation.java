package com.alumni.alumni_connect.dto;

import java.util.List;

public record MentorRecommendation(Long id, String name, String jobTitle, String company,
                                   List<String> skills, double relevanceEstimate,
                                   List<String> evidence, String uncertainty,
                                   String conversationTopic, String messageDraft) {}
