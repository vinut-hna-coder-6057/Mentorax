package com.alumni.alumni_connect.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlTlsUrlPostProcessorTest {
    @Test
    void requiresIdentityVerificationWhenMysqlUrlHasNoTlsMode() {
        assertEquals("jdbc:mysql://db.example.test:3306/mentorax?sslMode=VERIFY_IDENTITY",
                MySqlTlsUrlPostProcessor.requireIdentityVerification(
                        "jdbc:mysql://db.example.test:3306/mentorax"));
    }

    @Test
    void addsIdentityVerificationAfterExistingQueryOptions() {
        assertEquals("jdbc:mysql://db.example.test:3306/mentorax?connectTimeout=3000&sslMode=VERIFY_IDENTITY",
                MySqlTlsUrlPostProcessor.requireIdentityVerification(
                        "jdbc:mysql://db.example.test:3306/mentorax?connectTimeout=3000"));
    }

    @Test
    void preservesExplicitIdentityVerificationAndNormalizesItsNameAndValue() {
        assertEquals("jdbc:mysql://db.example.test/mentorax?connectTimeout=3000&sslMode=VERIFY_IDENTITY",
                MySqlTlsUrlPostProcessor.requireIdentityVerification(
                        "jdbc:mysql://db.example.test/mentorax?connectTimeout=3000&sslmode=verify_identity"));
    }

    @Test
    void rejectsWeakerTlsModesLegacyOptionsAndAmbiguousConfiguration() {
        for (String mode : new String[] {"PREFERRED", "REQUIRED", "VERIFY_CA", "DISABLED", ""}) {
            assertThrows(IllegalStateException.class, () ->
                    MySqlTlsUrlPostProcessor.requireIdentityVerification(
                            "jdbc:mysql://db.example.test/mentorax?sslMode=" + mode));
        }
        assertThrows(IllegalStateException.class, () ->
                MySqlTlsUrlPostProcessor.requireIdentityVerification(
                        "jdbc:mysql://db.example.test/mentorax?sslMode=VERIFY_IDENTITY&sslMode=VERIFY_IDENTITY"));
        assertThrows(IllegalStateException.class, () ->
                MySqlTlsUrlPostProcessor.requireIdentityVerification(
                        "jdbc:mysql://db.example.test/mentorax?sslMode=VERIFY_IDENTITY&useSSL=false"));
    }

    @Test
    void doesNotRewriteNonMysqlDatabaseUrls() {
        String url = "jdbc:h2:mem:mentorax-test";
        assertEquals(url, MySqlTlsUrlPostProcessor.requireIdentityVerification(url));
    }
}
