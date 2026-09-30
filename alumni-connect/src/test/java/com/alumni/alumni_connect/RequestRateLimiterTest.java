package com.alumni.alumni_connect;

import com.alumni.alumni_connect.service.RequestRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
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
}
