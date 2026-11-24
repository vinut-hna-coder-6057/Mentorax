package com.alumni.alumni_connect.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MySqlTlsUrlPostProcessorTest {
    private static final String MYSQL_URL = "jdbc:mysql://mysql.example.test:3306/mentorax";
    private static final String TRUSTSTORE_PASSWORD_PROPERTY =
            "spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword";
    private static final String TRUSTSTORE_URL_PROPERTY =
            "spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl";
    private static final String TRUSTSTORE_TYPE_PROPERTY =
            "spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType";

    @TempDir
    Path temporaryDirectory;

    @Test
    void requiredCertificateFixturesAreAvailableOnTheTestClasspath() {
        for (String name : new String[] {"test-ca.pem", "test-expired-ca.pem", "test-end-entity.pem"}) {
            try (InputStream fixture = getClass().getResourceAsStream("/tls/" + name)) {
                assertNotNull(fixture, "Missing test certificate fixture on test classpath: " + name);
            } catch (java.io.IOException exception) {
                throw new AssertionError("Could not read test certificate fixture: " + name, exception);
            }
        }
    }

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

    @Test
    void pemSecretAtStartupCreatesLoadablePkcs12ContainingProviderCaAndKeepsIdentityVerification() throws Exception {
        Path pem = copyFixture("test-ca.pem");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", MYSQL_URL)
                .withProperty("DB_CA_PEM_PATH", pem.toString());

        new MySqlTlsUrlPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertEquals(MYSQL_URL + "?sslMode=VERIFY_IDENTITY", environment.getProperty("spring.datasource.url"));
        assertEquals("false", environment.getProperty(
                "spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore"));
        assertEquals("PKCS12", environment.getProperty(TRUSTSTORE_TYPE_PROPERTY));
        Path generatedTruststore = Path.of(URI.create(environment.getProperty(TRUSTSTORE_URL_PROPERTY)));
        try {
            String password = environment.getProperty(TRUSTSTORE_PASSWORD_PROPERTY);
            assertNotNull(password);
            KeyStore keyStore = loadKeyStore(generatedTruststore, password, "PKCS12");
            assertEquals(1, keyStore.size());
            assertArrayEquals(readCa("test-ca.pem").getEncoded(), keyStore.getCertificate("provider-ca-0").getEncoded());
            assertPrivatePermissions(generatedTruststore);
        } finally {
            MySqlTlsUrlPostProcessor.cleanupTemporaryTruststore(
                    Path.of(URI.create(environment.getProperty(TRUSTSTORE_URL_PROPERTY))));
        }
    }

    @Test
    void pemPathTakesPrecedenceAndDoesNotRequireLegacyTruststorePassword() throws Exception {
        Map<String, Object> properties = MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                MYSQL_URL, "missing-legacy.p12", null, "PKCS12", copyFixture("test-ca.pem").toString());
        Path generatedTruststore = Path.of(URI.create((String) properties.get(TRUSTSTORE_URL_PROPERTY)));
        try {
            assertEquals("PKCS12", properties.get(TRUSTSTORE_TYPE_PROPERTY));
            assertEquals(MYSQL_URL + "?sslMode=VERIFY_IDENTITY", properties.get("spring.datasource.url"));
            loadKeyStore(generatedTruststore, (String) properties.get(TRUSTSTORE_PASSWORD_PROPERTY), "PKCS12");
        } finally {
            MySqlTlsUrlPostProcessor.cleanupTemporaryTruststore(generatedTruststore);
        }
    }

    @Test
    void missingPemPathStillRequiresTheExistingDirectTruststoreConfiguration() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> MySqlTlsUrlPostProcessor.verifiedMySqlProperties(MYSQL_URL, null, null, "PKCS12", null));
        assertTrue(exception.getMessage().contains("DB_CA_PEM_PATH or DB_CA_TRUSTSTORE_PATH is required"));
    }

    @Test
    void invalidEmptyExpiredAndNonCaPemInputsFailClosed() throws Exception {
        Path malformed = temporaryDirectory.resolve("malformed.pem");
        Files.writeString(malformed, "-----BEGIN CERTIFICATE-----\nnot a certificate\n-----END CERTIFICATE-----\n");
        Path empty = temporaryDirectory.resolve("empty.pem");
        Files.createFile(empty);

        for (Path invalidPem : new Path[] {
                malformed, empty, copyFixture("test-expired-ca.pem"), copyFixture("test-end-entity.pem")
        }) {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                            MYSQL_URL, null, null, null, invalidPem.toString()));
            assertFalse(exception.getMessage().isBlank());
        }
    }

    @Test
    void absentUnreadableOrNonexistentPemFailsClosed() throws Exception {
        Path missing = temporaryDirectory.resolve("missing-ca.pem");
        IllegalStateException missingException = assertThrows(IllegalStateException.class,
                () -> MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                        MYSQL_URL, null, null, null, missing.toString()));
        assertTrue(missingException.getMessage().contains("readable regular file"));

        Path directory = temporaryDirectory.resolve("not-a-file.pem");
        Files.createDirectory(directory);
        assertThrows(IllegalStateException.class, () -> MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                MYSQL_URL, null, null, null, directory.toString()));
    }

    @Test
    void legacyPkcs12AndJksTruststoresContinueToWorkAndWrongPasswordFails() throws Exception {
        X509Certificate ca = readCa("test-ca.pem");
        for (String type : new String[] {"PKCS12", "JKS"}) {
            String password = "unit-test-store-password";
            Path truststore = temporaryDirectory.resolve("legacy-" + type.toLowerCase() + "." + type.toLowerCase());
            createTruststore(truststore, type, password, ca);
            Map<String, Object> properties = MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                    MYSQL_URL, truststore.toString(), password, type);
            assertEquals(truststore.toUri().toString(), properties.get(TRUSTSTORE_URL_PROPERTY));
            assertEquals(type, properties.get(TRUSTSTORE_TYPE_PROPERTY));

            assertThrows(IllegalStateException.class, () ->
                    MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                            MYSQL_URL, truststore.toString(), "wrong-password", type));
        }
    }

    @Test
    void temporaryTruststoreCleanupDeletesTheGeneratedFile() throws Exception {
        Path pem = copyFixture("test-ca.pem");
        Map<String, Object> properties = MySqlTlsUrlPostProcessor.verifiedMySqlProperties(
                MYSQL_URL, null, null, null, pem.toString());
        Path generatedTruststore = Path.of(URI.create((String) properties.get(TRUSTSTORE_URL_PROPERTY)));

        assertTrue(Files.exists(generatedTruststore));
        MySqlTlsUrlPostProcessor.cleanupTemporaryTruststore(generatedTruststore);
        assertFalse(Files.exists(generatedTruststore));
    }

    private Path copyFixture(String name) throws Exception {
        Path destination = temporaryDirectory.resolve(name);
        try (InputStream input = getClass().getResourceAsStream("/tls/" + name)) {
            assertNotNull(input, "Missing test certificate fixture " + name);
            Files.copy(input, destination);
        }
        return destination;
    }

    private X509Certificate readCa(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/tls/" + name)) {
            assertNotNull(input, "Missing test certificate fixture " + name);
            return (X509Certificate) java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(input);
        }
    }

    private KeyStore loadKeyStore(Path path, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream input = Files.newInputStream(path)) {
            keyStore.load(input, password.toCharArray());
        }
        return keyStore;
    }

    private void createTruststore(Path path, String type, String password, X509Certificate ca) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        keyStore.load(null, password.toCharArray());
        keyStore.setCertificateEntry("test-ca", ca);
        try (var output = Files.newOutputStream(path)) {
            keyStore.store(output, password.toCharArray());
        }
    }

    private void assertPrivatePermissions(Path path) throws Exception {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions);
        }
    }
}
