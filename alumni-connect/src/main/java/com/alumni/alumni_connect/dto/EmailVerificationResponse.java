package com.alumni.alumni_connect.dto;

public record EmailVerificationResponse(
        boolean emailVerified,
        boolean approvalPending,
        String message) {}
