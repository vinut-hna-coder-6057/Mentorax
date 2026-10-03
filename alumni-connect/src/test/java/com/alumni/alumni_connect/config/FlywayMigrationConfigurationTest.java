package com.alumni.alumni_connect.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlywayMigrationConfigurationTest {
    @Test
    void freshHistorySelectsFreshChainAfterV2AndV3() {
        var history = List.of(
                entry("1", "create current schema", "SQL", true, 744378093),
                entry("2", "add one time password reset authorization", "SQL", true, -1002466783),
                entry("3", "align alumni profile approval status", "SQL", true, 1514469376));

        assertEquals(FlywayMigrationConfiguration.MigrationChain.FRESH,
                FlywayMigrationConfiguration.migrationChain(history));
    }

    @Test
    void exactProductionHistorySelectsFreshChainAndRejectsLegacyLocationOverride() {
        var productionHistory = List.of(
                entry("1", "create current schema", "SQL", true, 744378093),
                entry("2", "add one time password reset authorization", "SQL", true, -1002466783),
                entry("3", "align alumni profile approval status", "SQL", true, 1514469376));
        String freshLocations = "classpath:db/fresh,classpath:db/shared";

        var chain = FlywayMigrationConfiguration.migrationChain(productionHistory);

        assertEquals(FlywayMigrationConfiguration.MigrationChain.FRESH, chain);
        assertEquals(freshLocations, FlywayMigrationConfiguration.locationsFor(chain));
        org.junit.jupiter.api.Assertions.assertFalse(
                FlywayMigrationConfiguration.isKnownFailedNormalizedV2History(productionHistory));
        assertEquals(freshLocations, FlywayMigrationConfiguration.validateHistoryLocations(
                "classpath:db/fresh,classpath:db/shared", freshLocations));
        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.validateHistoryLocations(
                        "classpath:db/migration,classpath:db/shared", freshLocations));
    }

    @Test
    void legacyHistorySelectsLegacyChain() {
        var history = List.of(
                entry("1", "create alumni connect schema", "SQL", true),
                entry("2", "migrate legacy schema", "SQL", true),
                entry("3", "enforce relationships and indexes", "SQL", true),
                entry("8", "add one time password reset authorization", "SQL", true));

        assertEquals(FlywayMigrationConfiguration.MigrationChain.LEGACY,
                FlywayMigrationConfiguration.migrationChain(history));
    }

    @Test
    void normalizedHistorySelectsNormalizedChain() {
        var history = List.of(
                entry("1", "Existing normalized V1 baseline", "BASELINE", true),
                entry("2", "complete current baseline", "SQL", true),
                entry("3", "add one time password reset authorization", "SQL", true),
                entry("4", "align alumni profile approval status", "SQL", true));

        assertEquals(FlywayMigrationConfiguration.MigrationChain.NORMALIZED,
                FlywayMigrationConfiguration.migrationChain(history));
    }

    @Test
    void failedLegacyV2RequiresSchemaVerifiedRecoveryBeforeSelectingNormalizedChain() {
        var history = List.of(
                entry("1", "create alumni connect schema", "SQL", true, 934995385),
                entry("2", "migrate legacy schema", "SQL", false, -1728185645));

        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.migrationChain(history));
        assertEquals(FlywayMigrationConfiguration.MigrationChain.NORMALIZED,
                FlywayMigrationConfiguration.migrationChain(history, true));
        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.migrationChain(List.of(
                        history.get(0),
                        history.get(1),
                        entry("3", "add one time password reset authorization", "SQL", true)), true));
        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.migrationChain(List.of(
                        entry("1", "create alumni connect schema", "SQL", true, 1),
                        history.get(1)), true));
        org.junit.jupiter.api.Assertions.assertTrue(
                FlywayMigrationConfiguration.isKnownFailedNormalizedV2History(history));
    }

    @Test
    void migrationStrategyDoesNotInspectHistoryBeforeFlywayCreatesIt() {
        String url = "jdbc:h2:mem:empty_history";
        var dataSource = new DriverManagerDataSource(url, "sa", "");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/shared")
                .load();

        new FlywayMigrationConfiguration()
                .repairKnownFailedNormalizedV2(dataSource)
                .migrate(flyway);
    }

    @Test
    void loneHistoricalV1IsAmbiguousAndFailsClosed() {
        var history = List.of(entry("1", "create alumni connect schema", "SQL", true));

        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.migrationChain(history));
    }

    @Test
    void conflictingChainMarkersFailClosed() {
        var history = List.of(
                entry("1", "create current schema", "SQL", true),
                entry("2", "complete current baseline", "SQL", true));

        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.migrationChain(history));
    }

    @Test
    void unknownLaterMigrationDoesNotImplyLegacyChain() {
        var history = List.of(entry("5", "unrecognized migration", "SQL", true));

        assertThrows(IllegalStateException.class,
                () -> FlywayMigrationConfiguration.migrationChain(history));
    }

    @Test
    void currentFreshScriptsKeepProductionChecksums() {
        String url = "jdbc:h2:mem:checksums";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/fresh")
                .load();

        Map<String, Integer> checksums = java.util.Arrays.stream(flyway.info().all())
                .collect(Collectors.toMap(
                        migration -> migration.getVersion().getVersion(),
                        migration -> migration.getChecksum()));

        assertEquals(Map.of("1", 744378093, "2", -1002466783, "3", 1514469376), checksums);
    }

    private static FlywayMigrationConfiguration.HistoryEntry entry(
            String version, String description, String type, boolean success, int checksum) {
        return new FlywayMigrationConfiguration.HistoryEntry(version, description, type, success, checksum);
    }

    private static FlywayMigrationConfiguration.HistoryEntry entry(
            String version, String description, String type, boolean success) {
        return new FlywayMigrationConfiguration.HistoryEntry(version, description, type, success);
    }
}
