package com.alumni.alumni_connect.dto;

import java.util.List;

/** Minimal approved professional profile data used for the current ranking request. */
public record MentorCandidate(Long id, String name, String jobTitle, String company,
                              String bio, List<String> skills, String interests) {}
