package com.alumni.alumni_connect.dto.knowledge;

import java.time.LocalDateTime;

public record CompanionConversationResponse(long id, String title, LocalDateTime createdAt,
                                           LocalDateTime updatedAt) {}
