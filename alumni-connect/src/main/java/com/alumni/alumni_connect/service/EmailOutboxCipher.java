package com.alumni.alumni_connect.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class EmailOutboxCipher {
    private static final byte[] KEY_CONTEXT =
            "mentorax/email-outbox/v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public EmailOutboxCipher(@Value("${jwt.secret}") String jwtSecret) {
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(
                    jwtSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            key = new SecretKeySpec(derivation.doFinal(KEY_CONTEXT), "AES");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Email outbox encryption is unavailable", exception);
        }
    }

    public String encrypt(String value) {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length)
                            .put(iv)
                            .put(encrypted)
                            .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt email outbox payload", exception);
        }
    }

    public String decrypt(String value) {
        byte[] encoded = Base64.getDecoder().decode(value);
        if (encoded.length <= IV_LENGTH) {
            throw new IllegalStateException("Invalid email outbox payload");
        }
        byte[] iv = Arrays.copyOfRange(encoded, 0, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(encoded, IV_LENGTH, encoded.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt email outbox payload", exception);
        }
    }
}
