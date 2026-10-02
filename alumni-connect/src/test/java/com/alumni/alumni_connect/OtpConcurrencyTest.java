package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.Otp;
import com.alumni.alumni_connect.repository.OtpRepository;
import com.alumni.alumni_connect.service.OtpService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OtpConcurrencyTest {

    @Autowired private OtpRepository otpRepository;
    @Autowired private OtpService otpService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void concurrentValidVerificationsCanConsumeOtpOnlyOnce() throws Exception {
        String email = "otp-race-" + UUID.randomUUID() + "@example.test";
        Otp otp = new Otp();
        otp.setEmail(email);
        otp.setPurpose("PASSWORD_RESET");
        otp.setCodeHash(passwordEncoder.encode("123456"));
        otp.setExpiry(LocalDateTime.now().plusMinutes(5));
        otp.setAttemptCount(0);
        Otp stored = otpRepository.saveAndFlush(otp);

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = workers.submit(() -> verifyTogether(email, ready, start));
            Future<Boolean> second = workers.submit(() -> verifyTogether(email, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            boolean firstResult = first.get(15, TimeUnit.SECONDS);
            boolean secondResult = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstResult, secondResult,
                    "exactly one concurrent request should consume a valid OTP");

            Otp consumed = otpRepository.findById(stored.getId()).orElseThrow();
            assertTrue(consumed.isVerified());
            assertNotNull(consumed.getConsumedAt());
            assertFalse(otpService.verifyOtp(email, "123456", "PASSWORD_RESET"));
        } finally {
            start.countDown();
            workers.shutdownNow();
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    otpRepository.deleteByEmailAndPurpose(email, "PASSWORD_RESET"));
        }
    }

    private boolean verifyTogether(String email, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("verification start timed out");
        }
        return otpService.verifyOtp(email, "123456", "PASSWORD_RESET");
    }
}
