package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.ExperienceClaimProcessing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:experience_processing;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class ExperienceClaimProcessingRepositoryTransactionTest {
    @Autowired private ExperienceClaimProcessingRepository processingRepository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void acquiresLeaseWithoutAnOuterTransaction() {
        LocalDateTime now = LocalDateTime.now();
        processingRepository.saveAndFlush(
                new ExperienceClaimProcessing(101L, 1, now.minusSeconds(1)));

        assertEquals(1, processingRepository.acquire(101L, now, now.plusSeconds(60)));
        assertEquals(1, processingRepository.findById(101L).orElseThrow().getAttemptCount());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void refusesAnUnexpiredLeaseAndReacquiresAnExpiredLease() {
        LocalDateTime now = LocalDateTime.now();
        ExperienceClaimProcessing job = new ExperienceClaimProcessing(102L, 1, now.minusSeconds(1));
        job.setAttemptCount(1);
        job.setLeaseUntil(now.plusSeconds(60));
        processingRepository.saveAndFlush(job);

        assertEquals(0, processingRepository.acquire(102L, now, now.plusSeconds(60)));
        assertEquals(1, processingRepository.findById(102L).orElseThrow().getAttemptCount());

        job = processingRepository.findById(102L).orElseThrow();
        job.setLeaseUntil(now.minusSeconds(1));
        processingRepository.saveAndFlush(job);
        assertEquals(1, processingRepository.acquire(102L, now, now.plusSeconds(60)));
        assertEquals(2, processingRepository.findById(102L).orElseThrow().getAttemptCount());
    }
}
