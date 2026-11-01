package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.repository.ExperienceClaimProcessingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(
        name = "app.experience-verification.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ExperienceClaimProcessingScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExperienceClaimProcessingScheduler.class);
    private static final int MAX_BATCH_SIZE = 20;
    private final ExperienceClaimProcessingRepository processingRepository;
    private final ExperienceClaimProcessingService processingService;
    private final long leaseSeconds;

    public ExperienceClaimProcessingScheduler(
            ExperienceClaimProcessingRepository processingRepository,
            ExperienceClaimProcessingService processingService,
            @Value("${app.experience-verification.lease-seconds:1200}") long leaseSeconds) {
        this.processingRepository = processingRepository;
        this.processingService = processingService;
        this.leaseSeconds = Math.max(10, leaseSeconds);
    }

    @Scheduled(fixedDelayString = "${app.experience-verification.poll-delay-ms:5000}")
    public void processDueClaims() {
        LocalDateTime now = LocalDateTime.now();
        processingRepository.findDueClaimIds(now).stream()
                .limit(MAX_BATCH_SIZE)
                .forEach(claimId -> {
                    if (processingRepository.acquire(claimId, now, now.plusSeconds(leaseSeconds)) == 1) {
                        try {
                            processingService.processClaim(claimId);
                        } catch (RuntimeException exception) {
                            log.error("Experience verification job failed unexpectedly for claim {}",
                                    claimId, exception);
                            processingService.recordUnexpectedFailure(claimId);
                        }
                    }
                });
    }
}
