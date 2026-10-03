package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.EmailOutboxMessage;
import com.alumni.alumni_connect.repository.EmailOutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class EmailOutboxService {
    private static final Duration MESSAGE_TTL = Duration.ofMinutes(4);
    private static final Duration PROCESSING_LEASE = Duration.ofSeconds(30);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(2);

    public record ClaimedMessage(
            Long id,
            String recipient,
            String encryptedOtp,
            String purpose,
            int attemptCount,
            LocalDateTime expiresAt) {}

    private final EmailOutboxRepository repository;
    private final EmailOutboxCipher cipher;

    public EmailOutboxService(
            EmailOutboxRepository repository,
            EmailOutboxCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Transactional
    public void enqueueVerification(String email, String otp) {
        enqueueOtp(email, "EMAIL_VERIFICATION", otp);
    }

    @Transactional
    public void enqueueOtp(String email, String purpose, String otp) {
        if (!"EMAIL_VERIFICATION".equals(purpose)
                && !"PASSWORD_RESET".equals(purpose)) {
            throw new IllegalArgumentException("Unsupported email OTP purpose");
        }
        LocalDateTime now = LocalDateTime.now();
        repository.deleteByRecipientAndPurpose(email, purpose);
        repository.save(new EmailOutboxMessage(
                email,
                cipher.encrypt(otp),
                purpose,
                now,
                now.plus(MESSAGE_TTL)));
    }

    @Transactional
    public Optional<ClaimedMessage> claimNext(LocalDateTime now) {
        repository.deleteByExpiresAtLessThanEqual(now);
        return repository.findNextDueForUpdate(now, PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(message -> {
                    message.claim(now.plus(PROCESSING_LEASE));
                    return new ClaimedMessage(
                            message.getId(),
                            message.getRecipient(),
                            message.getEncryptedOtp(),
                            message.getPurpose(),
                            message.getAttemptCount(),
                            message.getExpiresAt());
                });
    }

    @Transactional
    public void markDelivered(Long id) {
        repository.deleteById(id);
    }

    @Transactional
    public void retryOrExpire(ClaimedMessage message, LocalDateTime now) {
        repository.findById(message.id()).ifPresent(outbox -> {
            Duration retryDelay = Duration.ofSeconds(
                    Math.min(30L * message.attemptCount(), MAX_RETRY_DELAY.toSeconds()));
            LocalDateTime nextAttempt = now.plus(retryDelay);
            if (!nextAttempt.isBefore(outbox.getExpiresAt())) {
                repository.delete(outbox);
                return;
            }
            outbox.deferUntil(nextAttempt);
        });
    }

}
