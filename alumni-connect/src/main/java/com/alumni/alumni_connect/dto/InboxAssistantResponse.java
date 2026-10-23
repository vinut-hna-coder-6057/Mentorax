package com.alumni.alumni_connect.dto;

import java.util.List;

public record InboxAssistantResponse(
        String schemaVersion,
        boolean available,
        String category,
        String categoryLabel,
        String confidence,
        boolean uncertain,
        List<String> categoryEvidence,
        String prioritySuggestion,
        String urgencyKind,
        String urgencyEvidence,
        String deadlineEvidence,
        String summary,
        String missingInformation,
        String draft,
        String draftKind,
        InboxAssistantMetadata modelMetadata,
        String message
) {
    public static InboxAssistantResponse unavailable() {
        return new InboxAssistantResponse("mentorax-inbox-assistant-v1", false,
                null, null, null, true, List.of(), null, null, null, null,
                null, null, null, null, null,
                "AI assistance is temporarily unavailable. You can continue reviewing and replying normally.");
    }
}
