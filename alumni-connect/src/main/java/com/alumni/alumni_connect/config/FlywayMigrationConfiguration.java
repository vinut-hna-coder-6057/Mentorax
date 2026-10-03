package com.alumni.alumni_connect.config;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnProperty(
    name = "spring.flyway.enabled",
    havingValue = "true",
    matchIfMissing = true
)
/** Routes database histories to immutable historical migrations or a matching baseline. */
public class FlywayMigrationConfiguration {
    private static final String LEGACY = "classpath:db/migration,classpath:db/shared";
    private static final String FRESH = "classpath:db/fresh,classpath:db/shared";
    private static final String NORMALIZED = "classpath:db/normalized,classpath:db/shared";

    @Bean
    FlywayConfigurationCustomizer migrationLocations(DataSource dataSource, Environment environment) {
        return configuration -> {
            String requestedLocations = environment.getProperty("spring.flyway.locations");
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                String catalog = connection.getCatalog();
                if (tableExists(metadata, catalog, "flyway_schema_history")) {
                    String historyLocations = locationsFor(historyState(connection));
                    configuration.locations(validateHistoryLocations(requestedLocations, historyLocations).split(","));
                    return;
                }

                // Explicit locations remain available for databases without Flyway history.
                if (requestedLocations != null) return;

                if (!tableExists(metadata, catalog, "users")) {
                    if (hasApplicationTables(metadata, catalog)) {
                        throw new IllegalStateException("The configured schema is non-empty but has no users table and no Flyway history; refusing to migrate it automatically.");
                    }
                    configuration.locations(FRESH.split(","));
                    return;
                }

                if (columnExists(metadata, catalog, "users", "password")) {
                    configuration.locations(LEGACY.split(","))
                            .baselineOnMigrate(true)
                            .baselineVersion(MigrationVersion.fromVersion("1"))
                            .baselineDescription("Pre-Flyway legacy baseline");
                } else if (isNormalizedSchema(metadata, catalog)) {
                    configuration.locations(NORMALIZED.split(","))
                            .baselineOnMigrate(true)
                            .baselineVersion(MigrationVersion.fromVersion("1"))
                            .baselineDescription("Existing normalized V1 baseline");
                } else {
                    throw new IllegalStateException("The configured schema has no Flyway history and does not match a recognized legacy or normalized Mentorax schema; refusing automatic migration.");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not inspect the configured schema to select a safe Flyway migration chain.", e);
            }
        };
    }

    /** Repair only the exact known failed legacy V2 entry on a verified normalized V1 schema. */
    @Bean
    FlywayMigrationStrategy repairKnownFailedNormalizedV2(DataSource dataSource) {
        return flyway -> {
            try (Connection connection = dataSource.getConnection()) {
                if (tableExists(connection.getMetaData(), connection.getCatalog(), "flyway_schema_history")) {
                    List<HistoryEntry> entries = readHistory(connection);
                    if (isKnownFailedNormalizedV2(connection, entries)) flyway.repair();
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Could not inspect failed Flyway history before migration.", e);
            }
            flyway.migrate();
        };
    }

    private static MigrationChain historyState(Connection connection) throws SQLException {
        List<HistoryEntry> entries = readHistory(connection);
        return migrationChain(entries, isKnownFailedNormalizedV2(connection, entries));
    }

    private static List<HistoryEntry> readHistory(Connection connection) throws SQLException {
        List<HistoryEntry> entries = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version, description, type, success, checksum FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                int checksum = rows.getInt("checksum");
                Integer migrationChecksum = rows.wasNull() ? null : checksum;
                entries.add(new HistoryEntry(
                        rows.getString("version"),
                        rows.getString("description"),
                        rows.getString("type"),
                        rows.getBoolean("success"),
                        migrationChecksum));
            }
        }
        return entries;
    }

    static MigrationChain migrationChain(List<HistoryEntry> entries) {
        return migrationChain(entries, false);
    }

    static MigrationChain migrationChain(List<HistoryEntry> entries, boolean knownFailedV2VerifiedAgainstSchema) {
        if (knownFailedV2VerifiedAgainstSchema && isKnownFailedNormalizedV2History(entries)) {
            return MigrationChain.NORMALIZED;
        }

        boolean legacy = false;
        boolean normalized = false;
        boolean fresh = false;
        boolean ambiguousV1 = false;

        for (HistoryEntry entry : entries) {
            if (!entry.success()) continue;
            String version = entry.version();
            String description = entry.description();
            String type = entry.type();

            if ("BASELINE".equalsIgnoreCase(type)) {
                if ("Pre-Flyway legacy baseline".equals(description) || "<< Flyway Baseline >>".equals(description)) {
                    legacy = true;
                } else if ("Existing normalized V1 baseline".equals(description)) {
                    normalized = true;
                }
            }
            if ("1".equals(version) && "create current schema".equals(description)) fresh = true;
            if ("1".equals(version) && "create alumni connect schema".equals(description)) ambiguousV1 = true;
            if ("2".equals(version) && description != null && description.startsWith("complete current baseline")) {
                normalized = true;
            }
            if ("2".equals(version) && "migrate legacy schema".equals(description)) legacy = true;
        }

        if ((fresh && (legacy || normalized)) || (legacy && normalized)) {
            throw new IllegalStateException("Conflicting Flyway history markers; refusing to choose or alter a migration chain automatically.");
        }
        if (fresh) return MigrationChain.FRESH;
        if (normalized) return MigrationChain.NORMALIZED;
        if (legacy) return MigrationChain.LEGACY;
        if (ambiguousV1) {
            throw new IllegalStateException("Ambiguous Flyway V1 history; refusing to choose or alter a migration chain automatically.");
        }
        throw new IllegalStateException("Unrecognized Flyway history in the configured schema; refusing to choose or alter a migration chain automatically.");
    }

    static boolean isKnownFailedNormalizedV2History(List<HistoryEntry> entries) {
        return entries.size() == 2
                && matches(entries.get(0), "1", "create alumni connect schema", true, 934995385)
                && matches(entries.get(1), "2", "migrate legacy schema", false, -1728185645);
    }

    private static boolean matches(
            HistoryEntry entry, String version, String description, boolean success, int checksum) {
        return version.equals(entry.version())
                && description.equals(entry.description())
                && "SQL".equalsIgnoreCase(entry.type())
                && entry.success() == success
                && Integer.valueOf(checksum).equals(entry.checksum());
    }

    static String locationsFor(MigrationChain chain) {
        return switch (chain) {
            case LEGACY -> LEGACY;
            case NORMALIZED -> NORMALIZED;
            case FRESH -> FRESH;
        };
    }

    static String validateHistoryLocations(String requestedLocations, String expectedLocations) {
        if (requestedLocations == null || requestedLocations.isBlank()) return expectedLocations;
        List<String> requested = java.util.Arrays.stream(requestedLocations.split(","))
                .map(String::trim)
                .filter(location -> !location.isEmpty())
                .toList();
        List<String> expected = java.util.Arrays.stream(expectedLocations.split(","))
                .map(String::trim)
                .toList();
        if (!requested.equals(expected)) {
            throw new IllegalStateException("Configured spring.flyway.locations conflict with the detected database migration history; remove the override or configure the matching migration chain.");
        }
        return expectedLocations;
    }

    private static boolean isKnownFailedNormalizedV2(Connection connection, List<HistoryEntry> entries)
            throws SQLException {
        return isKnownFailedNormalizedV2History(entries)
                && isNormalizedSchema(connection.getMetaData(), connection.getCatalog())
                && columnExists(connection.getMetaData(), connection.getCatalog(), "users", "password_hash")
                && !columnExists(connection.getMetaData(), connection.getCatalog(), "users", "password")
                && columnExists(connection.getMetaData(), connection.getCatalog(), "events", "created_by_email")
                && columnExists(connection.getMetaData(), connection.getCatalog(), "events", "created_by_id")
                && !columnExists(connection.getMetaData(), connection.getCatalog(), "events", "created_by");
    }

    private static boolean isNormalizedSchema(DatabaseMetaData metadata, String catalog) throws SQLException {
        return columnExists(metadata, catalog, "users", "password_hash")
                && columnExists(metadata, catalog, "events", "created_by_id")
                && tableExists(metadata, catalog, "student_profiles")
                && tableExists(metadata, catalog, "alumni_profiles")
                && tableExists(metadata, catalog, "conversations")
                && tableExists(metadata, catalog, "otp_verifications");
    }

    private static boolean hasApplicationTables(DatabaseMetaData metadata, String catalog) throws SQLException {
        try (ResultSet tables = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) if (!"flyway_schema_history".equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            return false;
        }
    }

    private static boolean tableExists(DatabaseMetaData metadata, String catalog, String name) throws SQLException {
        try (ResultSet rows = metadata.getTables(catalog, null, "%", new String[]{"TABLE"})) {
            while (rows.next()) if (name.equalsIgnoreCase(rows.getString("TABLE_NAME"))) return true;
            return false;
        }
    }

    private static boolean columnExists(DatabaseMetaData metadata, String catalog, String table, String column) throws SQLException {
        try (ResultSet rows = metadata.getColumns(catalog, null, "%", "%")) {
            while (rows.next()) {
                if (table.equalsIgnoreCase(rows.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(rows.getString("COLUMN_NAME"))) return true;
            }
            return false;
        }
    }

    enum MigrationChain {
        LEGACY,
        NORMALIZED,
        FRESH
    }

    record HistoryEntry(String version, String description, String type, boolean success, Integer checksum) {
        HistoryEntry(String version, String description, String type, boolean success) {
            this(version, description, type, success, null);
        }
    }
}
