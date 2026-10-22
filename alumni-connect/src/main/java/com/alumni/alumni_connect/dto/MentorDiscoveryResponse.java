package com.alumni.alumni_connect.dto;

import java.util.List;

public record MentorDiscoveryResponse(List<MentorRecommendation> recommendations,
                                      String rankingMethod, boolean fallbackUsed) {}
