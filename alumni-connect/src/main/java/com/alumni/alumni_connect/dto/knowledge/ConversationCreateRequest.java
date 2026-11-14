package com.alumni.alumni_connect.dto.knowledge;

import jakarta.validation.constraints.Size;

public record ConversationCreateRequest(@Size(max = 120) String title) {}
