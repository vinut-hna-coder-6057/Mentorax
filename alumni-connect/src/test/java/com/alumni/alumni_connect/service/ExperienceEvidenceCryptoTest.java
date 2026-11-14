package com.alumni.alumni_connect.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceEvidenceCryptoTest {
    private static final byte[] KEY = new byte[32];

    @Test
    void encryptsAndDecryptsEvidenceWithConfiguredAes256Key() {
        ExperienceEvidenceCrypto crypto = crypto(KEY);
        byte[] cleartext = "synthetic certificate evidence".getBytes(StandardCharsets.UTF_8);

        byte[] encrypted = crypto.encrypt(cleartext);

        assertFalse(Arrays.equals(cleartext, encrypted));
        assertArrayEquals(cleartext, crypto.decrypt(encrypted));
    }

    @Test
    void rejectsModifiedCiphertextAndWrongDecryptionKey() {
        byte[] encrypted = crypto(KEY).encrypt(new byte[]{1, 2, 3});
        byte[] modified = encrypted.clone();
        modified[modified.length - 1] ^= 1;
        byte[] modifiedNonce = encrypted.clone();
        modifiedNonce[0] ^= 1;
        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;

        assertThrows(IllegalStateException.class, () -> crypto(KEY).decrypt(modified));
        assertThrows(IllegalStateException.class, () -> crypto(KEY).decrypt(modifiedNonce));
        assertThrows(IllegalStateException.class, () -> crypto(wrongKey).decrypt(encrypted));
    }

    @Test
    void refusesMissingOrInvalidEncryptionConfiguration() {
        assertTrue(assertThrows(
                ResponseStatusException.class,
                () -> new ExperienceEvidenceCrypto("").encrypt(new byte[]{1}))
                .getStatusCode().is5xxServerError());
        assertThrows(
                ResponseStatusException.class,
                () -> new ExperienceEvidenceCrypto(Base64.getEncoder().encodeToString(new byte[16]))
                        .encrypt(new byte[]{1}));
    }

    private ExperienceEvidenceCrypto crypto(byte[] key) {
        return new ExperienceEvidenceCrypto(Base64.getEncoder().encodeToString(key));
    }
}
