package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.repository.ExperienceClaimProcessingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperienceClaimProcessingSchedulerTest {
    @Mock private ExperienceClaimProcessingRepository processingRepository;
    @Mock private ExperienceClaimProcessingService processingService;

    @Test
    void processesOnlyJobsSuccessfullyAcquiredByTheScheduler() {
        when(processingRepository.findDueClaimIds(any())).thenReturn(List.of(101L, 102L));
        when(processingRepository.acquire(anyLong(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(1, 0);

        new ExperienceClaimProcessingScheduler(processingRepository, processingService, 60)
                .processDueClaims();

        verify(processingService).processClaim(101L);
        verify(processingService, never()).processClaim(102L);
    }
}
