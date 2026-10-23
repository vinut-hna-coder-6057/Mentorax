package com.alumni.alumni_connect.dto;

public record InboxAssistantMetadata(String strategy, boolean trainedForMentorax,
                                     boolean scoresAreCalibratedProbabilities, String transformer) { }
