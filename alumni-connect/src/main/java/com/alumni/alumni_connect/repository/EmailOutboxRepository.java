package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.entity.EmailOutboxMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EmailOutboxRepository extends JpaRepository<EmailOutboxMessage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT message FROM EmailOutboxMessage message
            WHERE message.expiresAt > :now
              AND ((message.status = 'PENDING' AND message.nextAttemptAt <= :now)
                OR (message.status = 'PROCESSING' AND message.leaseUntil <= :now))
            ORDER BY message.id
            """)
    List<EmailOutboxMessage> findNextDueForUpdate(
            @Param("now") LocalDateTime now,
            Pageable pageable);

    void deleteByExpiresAtLessThanEqual(LocalDateTime now);

    void deleteByRecipient(String recipient);

    void deleteByRecipientAndPurpose(String recipient, String purpose);

    long countByRecipient(String recipient);

    Optional<EmailOutboxMessage> findFirstByRecipientOrderByIdDesc(String recipient);
}
