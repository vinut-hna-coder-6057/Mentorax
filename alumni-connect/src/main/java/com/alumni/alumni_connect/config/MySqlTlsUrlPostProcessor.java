package com.alumni.alumni_connect.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requires MySQL server identity verification and an explicit CA truststore before datasource startup. */
public final class MySqlTlsUrlPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String SOURCE_NAME = "mentoraxMySqlTlsUrl";
    private static final String TRUSTSTORE_PATH = "DB_CA_TRUSTSTORE_PATH";
    private static final String TRUSTSTORE_PASSWORD = "DB_CA_TRUSTSTORE_PASSWORD";
    private static final String TRUSTSTORE_TYPE = "DB_CA_TRUSTSTORE_TYPE";
    private static final String CA_PEM_PATH = "DB_CA_PEM_PATH";
    private static final int MAX_CA_FILE_BYTES = 1024 * 1024;
    private static final Pattern PEM_CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\r\\n]+?)\\s*-----END CERTIFICATE-----");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(URL_PROPERTY);
        if (url == null || !url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())) {
            return;
        }

        Map<String, Object> properties = verifiedMySqlProperties(url,
                environment.getProperty(TRUSTSTORE_PATH),
                environment.getProperty(TRUSTSTORE_PASSWORD),
                environment.getProperty(TRUSTSTORE_TYPE, "PKCS12"),
                environment.getProperty(CA_PEM_PATH));
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, properties));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    static String requireIdentityVerification(String url) {
        if (!url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())) return url;

        int queryStart = url.indexOf('?');
        if (queryStart < 0) return url + "?sslMode=VERIFY_IDENTITY";

        String query = url.substring(queryStart + 1);
        if (query.contains("?")) {
            throw new IllegalStateException("MySQL DB_URL contains an ambiguous query string");
        }
        int sslModeCount = 0;
        String sslMode = null;
        Set<String> parameterNames = new HashSet<>();
        for (String parameter : query.split("&", -1)) {
            int separator = parameter.indexOf('=');
            String name = separator < 0 ? parameter : parameter.substring(0, separator);
            String normalizedName = name.toLowerCase(Locale.ROOT);
            if (!parameterNames.add(normalizedName)) {
                throw new IllegalStateException("MySQL DB_URL contains duplicate or ambiguous query parameters");
            }
            if (name.equalsIgnoreCase("sslMode")) {
                sslModeCount++;
                sslMode = separator < 0 ? "" : parameter.substring(separator + 1);
            }
            if (name.equalsIgnoreCase("useSSL") || name.equalsIgnoreCase("requireSSL")
                    || name.equalsIgnoreCase("verifyServerCertificate")
                    || name.equalsIgnoreCase("trustCertificateKeyStoreUrl")
                    || name.equalsIgnoreCase("trustCertificateKeyStoreType")
                    || name.equalsIgnoreCase("trustCertificateKeyStorePassword")
                    || name.equalsIgnoreCase("fallbackToSystemTrustStore")) {
                throw new IllegalStateException("MySQL DB_URL must use sslMode=VERIFY_IDENTITY without legacy TLS options");
            }
        }

        if (sslModeCount > 1) {
            throw new IllegalStateException("MySQL DB_URL must contain exactly one TLS mode");
        }
        if (sslModeCount == 0) {
            return url + (query.isEmpty() ? "" : "&") + "sslMode=VERIFY_IDENTITY";
        }
        if (!"VERIFY_IDENTITY".equalsIgnoreCase(sslMode)) {
            throw new IllegalStateException("MySQL DB_URL must set sslMode=VERIFY_IDENTITY");
        }
        return replaceSslMode(url, queryStart, query);
    }

    static Map<String, Object> verifiedMySqlProperties(
            String url, String configuredPath, String password, String configuredType) {
        return verifiedMySqlProperties(url, configuredPath, password, configuredType, null);
    }

    static Map<String, Object> verifiedMySqlProperties(
            String url, String configuredPath, String password, String configuredType, String configuredPemPath) {
        String verifiedUrl = requireIdentityVerification(url);
        TruststoreSettings truststoreSettings;
        if (configuredPemPath != null && !configuredPemPath.isBlank()) {
            truststoreSettings = createTruststoreFromPem(configuredPemPath.trim());
        } else {
            String path = requiredTruststorePath(configuredPath);
            String truststorePassword = requiredSetting(TRUSTSTORE_PASSWORD, password);
            String type = (configuredType == null || configuredType.isBlank() ? "PKCS12" : configuredType)
                    .trim().toUpperCase(Locale.ROOT);
            truststoreSettings = new TruststoreSettings(validateTruststore(path, truststorePassword, type),
                    truststorePassword, type);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(URL_PROPERTY, verifiedUrl);
        properties.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl",
                truststoreSettings.path().toUri().toString());
        properties.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType",
                truststoreSettings.type());
        properties.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword",
                truststoreSettings.password());
        properties.put("spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore", "false");
        properties.put("spring.datasource.hikari.data-source-properties.connectionTimeZone", "+00:00");
        properties.put("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession", "true");
        return properties;
    }

    private static TruststoreSettings createTruststoreFromPem(String configuredPemPath) {
        List<X509Certificate> certificates = readCaCertificates(configuredPemPath);
        char[] password = randomPassword();
        Path truststore = null;
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, password);
            for (int index = 0; index < certificates.size(); index++) {
                keyStore.setCertificateEntry("provider-ca-" + index, certificates.get(index));
            }

            truststore = createPrivateTemporaryFile();
            try (OutputStream output = Files.newOutputStream(truststore, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                keyStore.store(output, password);
            }

            String passwordValue = new String(password);
            Path validated = validateTruststore(truststore.toString(), passwordValue, "PKCS12");
            registerTemporaryTruststoreCleanup(validated);
            return new TruststoreSettings(validated, passwordValue, "PKCS12");
        } catch (Exception exception) {
            if (truststore != null) {
                try {
                    cleanupTemporaryTruststore(truststore);
                } catch (Exception ignored) {
                    // Do not hide the startup failure if cleanup itself fails.
                }
            }
            if (exception instanceof IllegalStateException stateException) throw stateException;
            throw new IllegalStateException("Unable to create a temporary MySQL CA truststore", exception);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static List<X509Certificate> readCaCertificates(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException("DB_CA_PEM_PATH is missing or blank");
        }

        Path pemPath;
        try {
            pemPath = Path.of(configuredPath).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH is not a valid filesystem path", exception);
        }

        Path resolvedPemPath;
        try {
            // Resolve links once so a provider-mounted symlink is validated and read through its target.
            resolvedPemPath = pemPath.toRealPath();
        } catch (NoSuchFileException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH file does not exist or is a broken symbolic link", exception);
        } catch (AccessDeniedException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH file is not readable by the application user", exception);
        } catch (SecurityException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH file cannot be accessed by the application user", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH could not be resolved to an existing file", exception);
        }

        try {
            BasicFileAttributes attributes = Files.readAttributes(resolvedPemPath, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                throw new IllegalStateException("DB_CA_PEM_PATH resolved target is not a regular file");
            }
            if (!Files.isReadable(resolvedPemPath)) {
                throw new IllegalStateException("DB_CA_PEM_PATH resolved target is not readable by the application user");
            }
            long fileSize = Files.size(resolvedPemPath);
            if (fileSize == 0 || fileSize > MAX_CA_FILE_BYTES) {
                throw new IllegalStateException("DB_CA_PEM_PATH must contain a nonempty PEM CA bundle no larger than 1 MiB");
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(resolvedPemPath, StandardOpenOption.READ)) {
                bytes = input.readNBytes(MAX_CA_FILE_BYTES + 1);
            }
            if (bytes.length == 0 || bytes.length > MAX_CA_FILE_BYTES) {
                throw new IllegalStateException("DB_CA_PEM_PATH must contain a nonempty PEM CA bundle no larger than 1 MiB");
            }

            String pem = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            Matcher matcher = PEM_CERTIFICATE.matcher(pem);
            List<X509Certificate> certificates = new ArrayList<>();
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            int offset = 0;
            while (matcher.find()) {
                if (!pem.substring(offset, matcher.start()).isBlank()) {
                    throw new IllegalStateException("DB_CA_PEM_PATH contains invalid data outside PEM CA certificates");
                }
                byte[] der = Base64.getDecoder().decode(matcher.group(1).replaceAll("\\s", ""));
                X509Certificate certificate = (X509Certificate) certificateFactory.generateCertificate(
                        new java.io.ByteArrayInputStream(der));
                validateCaCertificate(certificate);
                certificates.add(certificate);
                offset = matcher.end();
            }
            if (certificates.isEmpty()) {
                throw new IllegalStateException("DB_CA_PEM_PATH contains malformed or invalid certificate data");
            }
            if (!pem.substring(offset).isBlank()) {
                throw new IllegalStateException("DB_CA_PEM_PATH contains invalid data outside PEM CA certificates");
            }
            return List.copyOf(certificates);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (NoSuchFileException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH resolved target no longer exists", exception);
        } catch (AccessDeniedException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH resolved target is not readable by the application user", exception);
        } catch (CharacterCodingException | CertificateException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH contains malformed or invalid certificate data", exception);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH contains malformed or invalid certificate data", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH could not be read or validated", exception);
        }
    }

    private static void validateCaCertificate(X509Certificate certificate) {
        try {
            certificate.checkValidity(new Date());
        } catch (CertificateException exception) {
            throw new IllegalStateException("DB_CA_PEM_PATH contains an expired or not-yet-valid certificate", exception);
        }
        if (certificate.getBasicConstraints() < 0) {
            throw new IllegalStateException("DB_CA_PEM_PATH contains a certificate that is not a CA");
        }
        boolean[] keyUsage = certificate.getKeyUsage();
        if (keyUsage != null && (keyUsage.length <= 5 || !keyUsage[5])) {
            throw new IllegalStateException("DB_CA_PEM_PATH contains a CA certificate not permitted to sign certificates");
        }
    }

    private static Path createPrivateTemporaryFile() throws Exception {
        Path temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir"));
        try {
            return Files.createTempFile(temporaryDirectory, "mentorax-mysql-ca-", ".p12",
                    PosixFilePermissions.asFileAttribute(Set.of(
                            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        } catch (UnsupportedOperationException exception) {
            return Files.createTempFile(temporaryDirectory, "mentorax-mysql-ca-", ".p12");
        }
    }

    private static void registerTemporaryTruststoreCleanup(Path path) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanupTemporaryTruststore(path),
                "mentorax-mysql-ca-cleanup"));
    }

    static void cleanupTemporaryTruststore(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Cleanup is best effort; truststore permissions restrict access until process exit.
        }
    }

    private static char[] randomPassword() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes).toCharArray();
    }

    private static String replaceSslMode(String url, int queryStart, String query) {
        StringBuilder normalized = new StringBuilder();
        for (String parameter : query.split("&", -1)) {
            if (!normalized.isEmpty()) normalized.append('&');
            int separator = parameter.indexOf('=');
            String name = separator < 0 ? parameter : parameter.substring(0, separator);
            if (name.equalsIgnoreCase("sslMode")) normalized.append("sslMode=VERIFY_IDENTITY");
            else normalized.append(parameter);
        }
        return url.substring(0, queryStart + 1) + normalized;
    }

    private static String requiredSetting(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for MySQL sslMode=VERIFY_IDENTITY");
        }
        return value.trim();
    }

    private static String requiredTruststorePath(String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException(
                    CA_PEM_PATH + " or " + TRUSTSTORE_PATH + " is required for MySQL sslMode=VERIFY_IDENTITY");
        }
        return configuredPath.trim();
    }

    private static Path validateTruststore(String configuredPath, String password, String type) {
        if (!type.equals("PKCS12") && !type.equals("JKS")) {
            throw new IllegalStateException("DB_CA_TRUSTSTORE_TYPE must be PKCS12 or JKS");
        }
        Path path;
        try {
            path = Path.of(configuredPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
                throw new IllegalStateException("DB_CA_TRUSTSTORE_PATH must be a readable regular file");
            }
            KeyStore keyStore = KeyStore.getInstance(type);
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                keyStore.load(input, password.toCharArray());
            }
            int caCertificates = 0;
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keyStore.isCertificateEntry(alias)) {
                    throw new IllegalStateException("DB_CA_TRUSTSTORE_PATH must contain trusted CA certificates only");
                }
                if (keyStore.getCertificate(alias) instanceof X509Certificate certificate) {
                    validateCaCertificate(certificate);
                    caCertificates++;
                } else {
                    throw new IllegalStateException("DB_CA_TRUSTSTORE_PATH must contain X.509 CA certificates only");
                }
            }
            if (caCertificates == 0) {
                throw new IllegalStateException("DB_CA_TRUSTSTORE_PATH must contain at least one trusted CA certificate");
            }
            return path;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "DB_CA_TRUSTSTORE_PATH could not be loaded; verify the CA truststore file, type, and password", exception);
        }
    }

    private record TruststoreSettings(Path path, String password, String type) { }
}
