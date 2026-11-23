package com.alumni.alumni_connect.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Requires MySQL server identity verification and an explicit CA truststore before datasource startup. */
public final class MySqlTlsUrlPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String SOURCE_NAME = "mentoraxMySqlTlsUrl";
    private static final String TRUSTSTORE_PATH = "DB_CA_TRUSTSTORE_PATH";
    private static final String TRUSTSTORE_PASSWORD = "DB_CA_TRUSTSTORE_PASSWORD";
    private static final String TRUSTSTORE_TYPE = "DB_CA_TRUSTSTORE_TYPE";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String url = environment.getProperty(URL_PROPERTY);
        if (url == null || !url.regionMatches(true, 0, "jdbc:mysql:", 0, "jdbc:mysql:".length())) {
            return;
        }

        Map<String, Object> properties = verifiedMySqlProperties(url,
                environment.getProperty(TRUSTSTORE_PATH),
                environment.getProperty(TRUSTSTORE_PASSWORD),
                environment.getProperty(TRUSTSTORE_TYPE, "PKCS12"));
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
        String verifiedUrl = requireIdentityVerification(url);
        String path = requiredSetting(TRUSTSTORE_PATH, configuredPath);
        String truststorePassword = requiredSetting(TRUSTSTORE_PASSWORD, password);
        String type = (configuredType == null || configuredType.isBlank() ? "PKCS12" : configuredType)
                .trim().toUpperCase(Locale.ROOT);
        Path truststore = validateTruststore(path, truststorePassword, type);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(URL_PROPERTY, verifiedUrl);
        properties.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreUrl",
                truststore.toUri().toString());
        properties.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStoreType", type);
        properties.put("spring.datasource.hikari.data-source-properties.trustCertificateKeyStorePassword",
                truststorePassword);
        properties.put("spring.datasource.hikari.data-source-properties.fallbackToSystemTrustStore", "false");
        properties.put("spring.datasource.hikari.data-source-properties.connectionTimeZone", "+00:00");
        properties.put("spring.datasource.hikari.data-source-properties.forceConnectionTimeZoneToSession", "true");
        return properties;
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
            try (InputStream input = Files.newInputStream(path)) {
                keyStore.load(input, password.toCharArray());
            }
            int caCertificates = 0;
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keyStore.isCertificateEntry(alias)) {
                    throw new IllegalStateException("DB_CA_TRUSTSTORE_PATH must contain trusted CA certificates only");
                }
                if (keyStore.getCertificate(alias) instanceof X509Certificate certificate
                        && certificate.getBasicConstraints() >= 0) {
                    caCertificates++;
                } else {
                    throw new IllegalStateException("DB_CA_TRUSTSTORE_PATH contains a certificate that is not a CA");
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
}
