package com.alumni.alumni_connect;

import com.alumni.alumni_connect.service.WebSocketMessageRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketMessageRateLimiterTest {
    @Test
    void rateLimitsAuthenticatedUserAcrossSessions() {
        WebSocketMessageRateLimiter limiter = new WebSocketMessageRateLimiter();
        ReflectionTestUtils.setField(limiter, "messagesPerWindow", 2);
        limiter.check("alice@example.test", "session-a");
        limiter.check("alice@example.test", "session-a");
        ResponseStatusException limited = assertThrows(ResponseStatusException.class,
                () -> limiter.check("alice@example.test", "session-a"));
        assertEquals(429, limited.getStatusCode().value());
        assertDoesNotThrow(() -> limiter.check("bob@example.test", "session-b"));
    }

    @Test
    void disconnectClearsSessionBucket() {
        WebSocketMessageRateLimiter limiter = new WebSocketMessageRateLimiter();
        ReflectionTestUtils.setField(limiter, "messagesPerWindow", 2);
        limiter.check("alice@example.test", "session-a");
        limiter.removeSession("session-a");
        assertDoesNotThrow(() -> limiter.check("alice@example.test", "session-a"));
    }

    @Test
    void expiredWindowAllowsAnotherSend() throws InterruptedException {
        WebSocketMessageRateLimiter limiter = new WebSocketMessageRateLimiter();
        ReflectionTestUtils.setField(limiter, "messagesPerWindow", 1);
        ReflectionTestUtils.setField(limiter, "windowMs", 1L);
        limiter.check("alice@example.test", "session-expiry");

        Thread.sleep(10);

        assertDoesNotThrow(() -> limiter.check("alice@example.test", "session-expiry"));
    }

    @Test
    void concurrentSendsCannotExceedPerUserLimit() throws Exception {
        WebSocketMessageRateLimiter limiter = new WebSocketMessageRateLimiter();
        ReflectionTestUtils.setField(limiter, "messagesPerWindow", 3);
        int attempts = 24;
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(attempts);
        try {
            List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < attempts; index++) {
                int session = index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent limiter test start timed out");
                    }
                    try {
                        limiter.check("alice@example.test", "session-" + session);
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
