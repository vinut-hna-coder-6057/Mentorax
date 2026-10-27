package com.alumni.alumni_connect.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class ExperienceEvidenceCrypto {
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private final String configuredKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ExperienceEvidenceCrypto(
            @Value("${EXPERIENCE_EVIDENCE_ENCRYPTION_KEY:}") String configuredKey) {
        this.configuredKey = configuredKey;
    }

    public byte[] encrypt(byte[] cleartext) {
        byte[] key = key();
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(cleartext);
            byte[] result = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, result, nonce.length, ciphertext.length);
            return result;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Evidence encryption failed", exception);
        }
    }

    public byte[] decrypt(byte[] stored) {
        byte[] key = key();
        if (stored == null || stored.length <= NONCE_LENGTH) {
            throw new IllegalStateException("Stored evidence is incomplete");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_LENGTH_BITS, stored, 0, NONCE_LENGTH));
            return cipher.doFinal(stored, NONCE_LENGTH, stored.length - NONCE_LENGTH);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Evidence decryption failed", exception);
        }
    }

    private byte[] key() {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Evidence encryption is not configured");
        }
        try {
            byte[] key = Base64.getDecoder().decode(configuredKey);
            if (key.length != 32) {
                throw new IllegalArgumentException("Invalid key length");
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Evidence encryption is not configured");
        }
    }
}
