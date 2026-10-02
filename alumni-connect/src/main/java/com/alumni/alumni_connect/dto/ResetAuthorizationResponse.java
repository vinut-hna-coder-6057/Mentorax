package com.alumni.alumni_connect.dto;

import java.time.LocalDateTime;

public record ResetAuthorizationResponse(String resetToken, LocalDateTime expiresAt) {}
