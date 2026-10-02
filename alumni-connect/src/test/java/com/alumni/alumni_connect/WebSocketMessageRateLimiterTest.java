package com.alumni.alumni_connect;

import com.alumni.alumni_connect.service.WebSocketMessageRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

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
}
