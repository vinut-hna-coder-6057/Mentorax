package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceCorroborationAttempt;
import com.alumni.alumni_connect.repository.ExperienceCorroborationAttemptRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmploymentCorroborationServiceTest {
    @Test
    void missingProviderLeavesIndependentStatusNotChecked() {
        var repository = mock(ExperienceCorroborationAttemptRepository.class);
        var service = new EmploymentCorroborationService(List.of(), repository);

        var result = service.verify(new ExperienceClaim());

        assertEquals("NOT_CHECKED", result.status());
        assertFalse(result.independentlyAuthenticated());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void onlyAuthenticatedProviderConfirmationProducesConfirmedStatus() {
        var repository = mock(ExperienceCorroborationAttemptRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        IndependentEmploymentVerificationProvider provider = new IndependentEmploymentVerificationProvider() {
            @Override
            public String providerId() { return "test-issuer"; }

            @Override
            public String verificationMethod() { return "AUTHORIZED_API"; }

            @Override
            public boolean supports(ExperienceClaim claim) { return true; }

            @Override
            public VerificationResponse verify(ExperienceClaim claim) {
                return new VerificationResponse("CONFIRMED", "ref-123", null, true);
            }
        };
        var service = new EmploymentCorroborationService(List.of(provider), repository);

        var result = service.verify(new ExperienceClaim());

        assertEquals("CONFIRMED", result.status());
        assertTrue(result.independentlyAuthenticated());
        verify(repository).saveAndFlush(any(ExperienceCorroborationAttempt.class));
        verify(repository).save(any(ExperienceCorroborationAttempt.class));
    }
}
