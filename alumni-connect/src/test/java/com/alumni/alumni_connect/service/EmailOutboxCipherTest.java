package com.alumni.alumni_connect.service;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailOutboxCipherTest {
    private final EmailOutboxCipher cipher =
            new EmailOutboxCipher("test-only-jwt-secret-with-sufficient-length");

    @Test
    void encryptsOtpWithFreshIvAndDecryptsIt() {
        String first = cipher.encrypt("123456");
        String second = cipher.encrypt("123456");

        assertNotEquals(first, second);
        assertNotEquals("123456", first);
        assertEquals("123456", cipher.decrypt(first));
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] encrypted = Base64.getDecoder().decode(cipher.encrypt("123456"));
        encrypted[encrypted.length - 1] ^= 1;

        assertThrows(
                IllegalStateException.class,
                () -> cipher.decrypt(Base64.getEncoder().encodeToString(encrypted)));
    }
}
