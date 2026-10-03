package com.alumni.alumni_connect.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(
        name = "app.email-outbox.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxDispatcher.class);

    private final EmailOutboxService outboxService;
    private final EmailOutboxCipher cipher;
    private final EmailService emailService;

    public EmailOutboxDispatcher(
            EmailOutboxService outboxService,
            EmailOutboxCipher cipher,
            EmailService emailService) {
        this.outboxService = outboxService;
        this.cipher = cipher;
        this.emailService = emailService;
    }

    @Scheduled(fixedDelayString = "${app.email-outbox.poll-delay-ms:5000}")
    public void dispatchNext() {
        outboxService.claimNext(LocalDateTime.now()).ifPresent(this::deliver);
    }

    private void deliver(EmailOutboxService.ClaimedMessage message) {
        try {
            String otp = cipher.decrypt(message.encryptedOtp());
            emailService.sendEmailVerificationOtp(message.recipient(), otp);
        } catch (RuntimeException exception) {
            log.warn(
                    "Queued verification email delivery deferred: outboxId={}, exceptionType={}",
                    message.id(),
                    exception.getClass().getSimpleName());
            outboxService.retryOrExpire(message, LocalDateTime.now());
            return;
        }
        outboxService.markDelivered(message.id());
    }
}
