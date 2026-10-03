package com.alumni.alumni_connect.config;

import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
            // An operator-supplied location is an intentional override.
            if (environment.getProperty("spring.flyway.locations") != null) return;
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                String catalog = connection.getCatalog();
                if (tableExists(metadata, catalog, "flyway_schema_history")) {
                    HistoryState history = historyState(connection);
                    if (history.legacy()) {
                        configuration.locations(LEGACY.split(","));
                    } else if (history.normalizedV1()) {
                        configuration.locations(NORMALIZED.split(","));
                    } else if (history.freshBaseline()) {
                        configuration.locations(FRESH.split(","));
                    } else {
                        throw new IllegalStateException("Unrecognized Flyway history in the configured schema; refusing to choose or alter a migration chain automatically.");
                    }
                    return;
                }

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

    /** Remove only the known failed V2 entry created when legacy V2 ran after normalized V1. */
    @Bean
    FlywayMigrationStrategy repairKnownFreshBaselineFailure(DataSource dataSource) {
        return flyway -> {
            try (Connection connection = dataSource.getConnection()) {
                if (isKnownFailedFreshV2(connection)) flyway.repair();
            } catch (SQLException e) {
                throw new IllegalStateException("Could not inspect failed Flyway history before migration.", e);
            }
            flyway.migrate();
        };
    }

    private static HistoryState historyState(Connection connection) throws SQLException {
        boolean legacy = false;
        boolean normalized = false;
        boolean fresh = false;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version, description, type, success FROM flyway_schema_history ORDER BY installed_rank")) {
            while (rows.next()) {
                String version = rows.getString("version");
                String description = rows.getString("description");
                String type = rows.getString("type");
                boolean success = rows.getBoolean("success");
                if ("BASELINE".equalsIgnoreCase(type)) {
                    if ("Pre-Flyway legacy baseline".equals(description) || "<< Flyway Baseline >>".equals(description)) legacy = true;
                    else if ("Existing normalized V1 baseline".equals(description)) normalized = true;
                }
                if (!success) continue;
                if (version != null && !"1".equals(version)) {
                    if (description != null && description.startsWith("complete current baseline")) normalized = true;
                    else legacy = true;
                }
                if ("1".equals(version) && "create current schema".equals(description)) fresh = true;
                if ("1".equals(version) && "create alumni connect schema".equals(description)) normalized = true;
            }
        }
        return new HistoryState(legacy, normalized, fresh);
    }

    private static boolean isKnownFailedFreshV2(Connection connection) throws SQLException {
        if (!tableExists(connection.getMetaData(), connection.getCatalog(), "flyway_schema_history")) return false;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version, description, type, success FROM flyway_schema_history")) {
            boolean baselineV1 = false;
            boolean failedLegacyV2 = false;
            int failedRows = 0;
            while (rows.next()) {
                String version = rows.getString("version");
                String description = rows.getString("description");
                String type = rows.getString("type");
                boolean success = rows.getBoolean("success");
                if ("1".equals(version) && success && "SQL".equalsIgnoreCase(type)
                        && "create alumni connect schema".equals(description)) baselineV1 = true;
                if (!success) {
                    failedRows++;
                    if ("2".equals(version) && "SQL".equalsIgnoreCase(type)
                            && "migrate legacy schema".equals(description)) failedLegacyV2 = true;
                }
            }
            return baselineV1 && failedLegacyV2 && failedRows == 1
                    && isNormalizedSchema(connection.getMetaData(), connection.getCatalog())
                    && columnExists(connection.getMetaData(), connection.getCatalog(), "users", "password_hash")
                    && !columnExists(connection.getMetaData(), connection.getCatalog(), "users", "password")
                    && columnExists(connection.getMetaData(), connection.getCatalog(), "events", "created_by_email")
                    && columnExists(connection.getMetaData(), connection.getCatalog(), "events", "created_by_id")
                    && !columnExists(connection.getMetaData(), connection.getCatalog(), "events", "created_by");
        }
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

    private record HistoryState(boolean legacy, boolean normalizedV1, boolean freshBaseline) { }
}
