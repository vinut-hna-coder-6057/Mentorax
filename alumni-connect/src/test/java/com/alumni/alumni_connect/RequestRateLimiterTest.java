package com.alumni.alumni_connect;

import com.alumni.alumni_connect.service.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RequestRateLimiterTest {
    @Test void throttlesRepeatedLoginAttemptsByAccountAndIp() {
        RequestRateLimiter limiter = new RequestRateLimiter();
        ReflectionTestUtils.setField(limiter,"loginIpLimit",3);
        ReflectionTestUtils.setField(limiter,"loginEmailLimit",2);
        limiter.check("login","192.0.2.8","person@example.com");
        limiter.check("login","192.0.2.8","person@example.com");
        assertThrows(ResponseStatusException.class,()->limiter.check("login","192.0.2.8","person@example.com"));
    }

    @Test void allowsRequestsBelowLimitAndThrottlesRepeatedAccountAndIpRequests() {
        RequestRateLimiter limiter = new RequestRateLimiter();
        ReflectionTestUtils.setField(limiter,"otpIpLimit",3);
        ReflectionTestUtils.setField(limiter,"otpEmailLimit",2);
        limiter.check("otp","192.0.2.1","person@example.com");
        limiter.check("otp","192.0.2.1","person@example.com");
        assertThrows(ResponseStatusException.class,()->limiter.check("otp","192.0.2.1","person@example.com"));
        assertDoesNotThrow(()->limiter.check("otp","192.0.2.2","other@example.com"));
    }

    @Test
    void expiredWindowAllowsRequestsAgain() throws InterruptedException {
        RequestRateLimiter limiter = new RequestRateLimiter();
        ReflectionTestUtils.setField(limiter, "loginIpLimit", 1);
        ReflectionTestUtils.setField(limiter, "loginEmailLimit", 1);
        ReflectionTestUtils.setField(limiter, "windowMs", 1L);
        limiter.check("login", "192.0.2.10", "person@example.com");

        Thread.sleep(10);

        assertDoesNotThrow(() -> limiter.check("login", "192.0.2.10", "person@example.com"));
    }

    @Test
    void concurrentRequestsCannotExceedAccountLimit() throws Exception {
        RequestRateLimiter limiter = new RequestRateLimiter();
        ReflectionTestUtils.setField(limiter, "loginIpLimit", 100);
        ReflectionTestUtils.setField(limiter, "loginEmailLimit", 3);
        int attempts = 24;
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(attempts);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent limiter test start timed out");
                    }
                    try {
                        limiter.check("login", "192.0.2.11", "person@example.com");
                        return true;
                    } catch (ResponseStatusException limited) {
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long allowed = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) allowed++;
            assertEquals(3, allowed);
        } finally {
            executor.shutdownNow();
        }
    }
}
