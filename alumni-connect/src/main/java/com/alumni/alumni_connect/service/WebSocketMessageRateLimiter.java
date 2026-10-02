package com.alumni.alumni_connect.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Process-local STOMP send limiter. Use a shared store when running multiple instances. */
@Component
public class WebSocketMessageRateLimiter {
    private record Bucket(long windowStart, int count) {}
    private static final int MAX_SESSIONS = 20_000;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Clock clock = Clock.systemUTC();

    @Value("${app.websocket.rate-limit.messages:60}")
    private int messagesPerWindow = 60;
    @Value("${app.websocket.rate-limit.window-ms:60000}")
    private long windowMs = 60_000;

    public synchronized void check(String user, String sessionId) {
        if (user == null || user.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated WebSocket session required");
        }
        if (messagesPerWindow < 1 || windowMs < 1) {
            throw new IllegalStateException("WebSocket rate limit configuration must be positive");
        }
        long now = clock.millis();
        hit("user:" + user.toLowerCase(java.util.Locale.ROOT), now);
        hit("session:" + sessionId, now);
    }

    private void hit(String key, long now) {
        Bucket old = buckets.get(key);
        if (old == null || now - old.windowStart() >= windowMs) {
            prune(now);
            if (buckets.size() >= MAX_SESSIONS) throw limited();
            buckets.put(key, new Bucket(now, 1));
        } else if (old.count() >= messagesPerWindow) {
            throw limited();
        } else {
            buckets.put(key, new Bucket(old.windowStart(), old.count() + 1));
        }
    }

    public synchronized void removeSession(String sessionId) {
        if (sessionId == null) return;
        buckets.remove("session:" + sessionId);
    }

    private void prune(long now) {
        Iterator<Bucket> iterator = buckets.values().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().windowStart() >= windowMs) iterator.remove();
        }
    }

    private ResponseStatusException limited() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many WebSocket messages");
    }
}
