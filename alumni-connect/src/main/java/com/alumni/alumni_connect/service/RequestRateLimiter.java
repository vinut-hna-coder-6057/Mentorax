package com.alumni.alumni_connect.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Process-local fixed-window limiter. Use a shared store before running multiple app instances. */
@Component
public class RequestRateLimiter {
    private record Bucket(long start, int count) {}
    private static final int MAX_KEYS = 20_000;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();
    private final Clock clock = Clock.systemUTC();
    @Value("${app.rate-limit.login-ip:60}") private int loginIpLimit = 60;
    @Value("${app.rate-limit.login-email:10}") private int loginEmailLimit = 10;
    @Value("${app.rate-limit.otp-ip:20}") private int otpIpLimit = 20;
    @Value("${app.rate-limit.otp-email:5}") private int otpEmailLimit = 5;
    @Value("${app.rate-limit.window-ms:900000}") private long windowMs = 900_000;

    public synchronized void check(String action, String ip, String email) {
        long now = clock.millis();
        int ipLimit = action.equals("login") ? loginIpLimit : otpIpLimit;
        int accountLimit = action.equals("login") ? loginEmailLimit : otpEmailLimit;
        hit(action + ":ip:" + normalize(ip), ipLimit, now);
        if (email != null && !email.isBlank()) hit(action + ":email:" + normalize(email), accountLimit, now);
    }

    private void hit(String key, int limit, long now) {
        if (limit < 1 || windowMs < 1) throw new IllegalStateException("Rate limit configuration must be positive");
        Bucket old = buckets.get(key);
        if (old == null || now - old.start() >= windowMs) {
            prune(now);
            if (buckets.size() >= MAX_KEYS) throw limited();
            buckets.put(key, new Bucket(now, 1));
        } else if (old.count() >= limit) {
            throw limited();
        } else {
            buckets.put(key, new Bucket(old.start(), old.count() + 1));
        }
    }

    private void prune(long now) {
        Iterator<Bucket> it = buckets.values().iterator();
        while (it.hasNext()) if (now - it.next().start() >= windowMs) it.remove();
    }

    private String normalize(String value) { return value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT); }
    private ResponseStatusException limited() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"); }
}
