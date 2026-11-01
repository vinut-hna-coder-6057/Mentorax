package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.ExperienceClaim;
import com.alumni.alumni_connect.entity.ExperienceCorroborationAttempt;
import com.alumni.alumni_connect.repository.ExperienceCorroborationAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class EmploymentCorroborationService {
    private static final Logger log = LoggerFactory.getLogger(EmploymentCorroborationService.class);
    private final List<IndependentEmploymentVerificationProvider> providers;
    private final ExperienceCorroborationAttemptRepository attemptRepository;

    public EmploymentCorroborationService(
            List<IndependentEmploymentVerificationProvider> providers,
            ExperienceCorroborationAttemptRepository attemptRepository) {
        this.providers = providers;
        this.attemptRepository = attemptRepository;
    }

    public Result verify(ExperienceClaim claim) {
        IndependentEmploymentVerificationProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(claim))
                .findFirst()
                .orElse(null);
        if (provider == null) return new Result("NOT_CHECKED", false);

        LocalDateTime requestedAt = LocalDateTime.now();
        var attempt = attemptRepository.saveAndFlush(new ExperienceCorroborationAttempt(
                claim.getId(), bounded(provider.providerId(), 100),
                bounded(provider.verificationMethod(), 100), requestedAt));
        try {
            var response = provider.verify(claim);
            attempt.setResponseAt(LocalDateTime.now());
            attempt.setResult(boundedResult(response.result()));
            attempt.setReferenceIdentifier(boundedNullable(response.referenceIdentifier(), 255));
            attempt.setFailureReason(boundedNullable(response.failureReason(), 500));
            boolean authenticated = response.independentlyAuthenticated()
                    && "CONFIRMED".equals(response.result());
            attempt.setIndependentlyAuthenticated(authenticated);
            attemptRepository.save(attempt);
            return new Result(authenticated ? "CONFIRMED" : "NOT_CHECKED", authenticated);
        } catch (RuntimeException exception) {
            log.warn("Independent employment verification provider {} failed for claim {}",
                    provider.providerId(), claim.getId(), exception);
            attempt.setResponseAt(LocalDateTime.now());
            attempt.setResult("UNAVAILABLE");
            attempt.setFailureReason("Provider request failed.");
            attempt.setIndependentlyAuthenticated(false);
            attemptRepository.save(attempt);
            return new Result("NOT_CHECKED", false);
        }
    }

    private static String boundedResult(String result) {
        return switch (result) {
            case "CONFIRMED", "NOT_CONFIRMED", "INCONCLUSIVE", "UNAVAILABLE" -> result;
            default -> "INCONCLUSIVE";
        };
    }

    private static String bounded(String value, int maxLength) {
        if (value == null || value.isBlank()) return "UNSPECIFIED";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static String boundedNullable(String value, int maxLength) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record Result(String status, boolean independentlyAuthenticated) {}
}
