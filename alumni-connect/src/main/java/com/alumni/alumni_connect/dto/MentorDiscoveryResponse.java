package com.alumni.alumni_connect.dto;

import java.util.List;

public record MentorDiscoveryResponse(List<MentorRecommendation> recommendations,
                                      String rankingMethod, boolean fallbackUsed,
                                      QuestionIntent intent, String answer,
                                      boolean needsClarification, String clarificationQuestion,
                                      String message, List<String> sources) {
    public MentorDiscoveryResponse(List<MentorRecommendation> recommendations,
                                   String rankingMethod, boolean fallbackUsed) {
        this(recommendations, rankingMethod, fallbackUsed,
                QuestionIntent.CAREER_MENTOR_SEARCH, null, false, null, null, List.of());
    }
}
