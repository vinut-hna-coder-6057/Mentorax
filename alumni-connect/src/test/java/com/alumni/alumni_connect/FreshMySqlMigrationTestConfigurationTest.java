package com.alumni.alumni_connect;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshMySqlMigrationTestConfigurationTest {
    private static final String PREFIX = "MENTORAX_MYSQL_MIGRATION_TEST";
    private static final String TEST_USERNAME = "mentorax_migration_test_user";
    private static final String TEST_PASSWORD = "not-a-real-password";

    @Test
    void acceptsExplicitNonRootAccountForDedicatedMigrationSchema() {
        FreshMySqlMigrationTest.DatabaseSettings settings = FreshMySqlMigrationTest.settings(
                PREFIX, environment("jdbc:mysql://127.0.0.1:3306/mentorax_migration_test"));

        assertEquals("mentorax_migration_test", settings.schema());
        assertEquals(TEST_USERNAME, settings.username());
        assertEquals(TEST_PASSWORD, settings.password());
    }

    @Test
    void acceptsOnlyTheDedicatedRecoverySchemaForRecoveryConfiguration() {
        FreshMySqlMigrationTest.DatabaseSettings settings = FreshMySqlMigrationTest.settings(
                "MENTORAX_MYSQL_RECOVERY_TEST",
                environment("jdbc:mysql://localhost:3306/mentorax_recovery_test",
                        "MENTORAX_MYSQL_RECOVERY_TEST"));

        assertEquals("mentorax_recovery_test", settings.schema());
        assertEquals(TEST_USERNAME, settings.username());
    }

    @Test
    void rejectsMissingCredentialsInsteadOfSkippingTheMySqlTest() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> FreshMySqlMigrationTest.settings(PREFIX, Map.of()));

        assertTrue(exception.getMessage().contains(PREFIX));
        assertTrue(exception.getMessage().contains("will not run"));
    }

    @Test
    void rejectsOtherSchemasHostsPortsAndCredentialsEmbeddedInTheUrl() {
        for (String url : new String[]{
                "jdbc:mysql://localhost:3306/mentorax",
                "jdbc:mysql://db.example.com:3306/mentorax_migration_test",
                "jdbc:mysql://localhost:3307/mentorax_migration_test",
                "jdbc:mysql://user:pass@localhost:3306/mentorax_migration_test",
                "jdbc:mysql://localhost:3306/mentorax_migration_test?socketFactory=custom"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> FreshMySqlMigrationTest.settings(PREFIX, environment(url)), url);
        }
    }

    @Test
    void rejectsAnUnrecognizedConfigurationPrefix() {
        assertThrows(IllegalArgumentException.class,
                () -> FreshMySqlMigrationTest.settings("OTHER_DATABASE", environment(
                        "jdbc:mysql://localhost:3306/mentorax_migration_test")));
    }

    private static Map<String, String> environment(String url) {
        return environment(url, PREFIX);
    }

    private static Map<String, String> environment(String url, String prefix) {
        Map<String, String> environment = new HashMap<>();
        environment.put(prefix + "_URL", url);
        environment.put(prefix + "_USERNAME", TEST_USERNAME);
        environment.put(prefix + "_PASSWORD", TEST_PASSWORD);
        return environment;
    }
}
