package com.alumni.alumni_connect;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.junit.jupiter.api.Assumptions;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Optional real-MySQL migration and Hibernate validation; only targets an explicitly supplied empty schema. */
class FreshMySqlMigrationTest {
    private static final List<String> REQUIRED_TABLES = List.of(
            "users", "student_profiles", "alumni_profiles", "skills", "user_skills", "events",
            "event_registrations", "connections", "conversations", "conversation_participants",
            "messages", "notifications", "otp_verifications");

    @Test
    void freshMySqlSchemaMigratesAndPassesHibernateValidation() throws Exception {
        String url = System.getenv("MENTORAX_MYSQL_MIGRATION_TEST_URL");
        String username = System.getenv("MENTORAX_MYSQL_MIGRATION_TEST_USERNAME");
        String password = System.getenv("MENTORAX_MYSQL_MIGRATION_TEST_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank() && username != null && !username.isBlank() && password != null,
                "Set disposable MySQL test URL, username, and password to enable this integration test.");

        try (var connection = DriverManager.getConnection(url, username, password);
             var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            List<String> existing = new ArrayList<>();
            while (tables.next()) existing.add(tables.getString("TABLE_NAME"));
            assertTrue(existing.isEmpty(), "Refusing to run the fresh migration test against a non-empty database: " + existing);
        }

        try (ConfigurableApplicationContext ignored = startApplication(url, username, password)) {
            try (var connection = DriverManager.getConnection(url, username, password);
                 var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                List<String> actual = new ArrayList<>();
                while (tables.next()) actual.add(tables.getString("TABLE_NAME").toLowerCase());
                assertTrue(actual.containsAll(REQUIRED_TABLES), "Fresh MySQL schema is missing required tables: " + REQUIRED_TABLES.stream().filter(t -> !actual.contains(t)).toList());
            }
        }
    }

    @Test
    void knownFailedLegacyV2AfterNormalizedV1IsRepairedAndMigrated() throws Exception {
        String url = System.getenv("MENTORAX_MYSQL_RECOVERY_TEST_URL");
        String username = System.getenv("MENTORAX_MYSQL_RECOVERY_TEST_USERNAME");
        String password = System.getenv("MENTORAX_MYSQL_RECOVERY_TEST_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank() && username != null && !username.isBlank() && password != null,
                "Set disposable MySQL recovery URL, username, and password to enable this integration test.");

        try (var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 0 AND description = 'migrate legacy schema'")) {
            assertTrue(rows.next() && rows.getInt(1) == 1, "Recovery test requires the known failed legacy V2 history row");
        }

        try (ConfigurableApplicationContext ignored = startApplication(url, username, password);
             var connection = DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1 AND description = 'complete current baseline'")) {
            assertTrue(rows.next() && rows.getInt(1) == 1, "Normalized completion migration V2 was not applied");
            try (var failedRows = statement.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0")) {
                assertTrue(failedRows.next() && failedRows.getInt(1) == 0, "Failed history rows remain after recovery");
            }
        }
    }

    private static ConfigurableApplicationContext startApplication(String url, String username, String password) {
        return new SpringApplicationBuilder(AlumniConnectApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.main.banner-mode=off", "server.port=0")
                .run(
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password,
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
                        "--jwt.secret=migration-test-secret-that-is-at-least-32-characters-long",
                        "--app.websocket.allowed-origins=http://localhost:*",
                        "--app.cors.allowed-origin-patterns=http://localhost:*");
    }
}
