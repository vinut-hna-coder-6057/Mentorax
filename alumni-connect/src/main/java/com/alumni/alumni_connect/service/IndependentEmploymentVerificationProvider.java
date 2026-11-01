package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;

public interface IndependentEmploymentVerificationProvider {
    String providerId();

    String verificationMethod();

    boolean supports(ExperienceClaim claim);

    VerificationResponse verify(ExperienceClaim claim);

    record VerificationResponse(
            String result,
            String referenceIdentifier,
            String failureReason,
            boolean independentlyAuthenticated) {}
}
