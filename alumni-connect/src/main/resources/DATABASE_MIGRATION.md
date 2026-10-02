# Database migrations

Flyway is enabled for normal application startup, and Hibernate uses `ddl-auto=validate`. Hibernate checks the schema; it does not create or update production tables.

## Migration routing

`FlywayMigrationConfiguration` inspects only the configured schema metadata and Flyway history before selecting locations:

| Detected database | Flyway locations | Action |
| --- | --- | --- |
| Empty schema | `db/fresh`, `db/shared` | Run the complete current schema baseline in `db/fresh/V1__create_current_schema.sql`. |
| Historical legacy chain (baseline at version 1, or successful legacy V2+) | `db/migration`, `db/shared` | Keep the original V1–V7 migration identities and checksums; apply pending legacy-chain migrations. |
| Existing normalized V1 without the legacy chain | `db/normalized`, `db/shared` | Validate the copied V1 checksum, then run V2 to backfill profile data and add the current hardening. |
| Pre-Flyway legacy schema with `users.password` | `db/migration`, `db/shared` | Baseline at version 1, then run the legacy conversion and hardening migrations. |
| Unknown or inconsistent schema/history | None | Startup stops before migration; inspect and resolve the history explicitly. |

The repository’s local MySQL 8.0.46 history was inspected during this change. It contains Flyway’s version-1 baseline marker and successful V2–V6 rows; V7 is pending. That database uses the historical legacy chain. The old migration files were therefore left byte-for-byte unchanged. `db/normalized/V1__create_alumni_connect_schema.sql` is a byte-identical copy of historical V1 so a database that actually applied V1 can retain its checksum.

The fresh V1 includes the normalized schema plus the conversation-pair and canonical-connection constraints currently supplied by legacy V5 and V7. V1 already includes the `email_verified` column and `messages.conversation_id` foreign key; those operations are not repeated in the fresh baseline. Profile data backfill is run only for databases that already contain user rows.

New schema changes should be added as migrations in `db/shared` with a version after V7. The shared location is used by each routed history, so a new migration runs once regardless of how the database was initialized. Keep the historical files in `db/migration` immutable.

## Existing database deployment

Before deployment:

1. Back up the database and verify the selected database name in `DB_URL`.
2. Inspect `flyway_schema_history` and confirm its versions, descriptions, success flags, and checksums against the expected route above.
3. For the legacy route, confirm migration V2–V6 have succeeded. The repository’s inspected local database has exactly this state. V7 creates generated canonical pair columns and a unique index; check that no opposite-direction connection pair duplicates exist before applying it. Do not delete existing connection rows automatically.
4. Start the application with Flyway enabled and Hibernate validation enabled. Review Flyway’s migration output and the application startup result.

For a pre-Flyway legacy schema, the legacy route uses Flyway’s baseline-on-migrate at version 1. V1 is skipped for that database; V2 onward performs the legacy conversion. Orphaned relationships, missing expected legacy columns, or an unknown partial schema stop migration and need a reviewed recovery plan.

## Fresh database verification

Create an empty disposable MySQL 8 schema, configure `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for it, and start the application with:

```text
spring.flyway.enabled=true
spring.jpa.hibernate.ddl-auto=validate
```

Verify Flyway history and tables:

```sql
SELECT installed_rank, version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
SHOW TABLES;
```

The schema must include users, student_profiles, alumni_profiles, skills, user_skills, events, event_registrations, connections, conversations, conversation_participants, messages, notifications, and otp_verifications. Inspect the important table definitions with `SHOW CREATE TABLE` before promoting the configuration.

An optional real-MySQL startup test can be run against an empty disposable schema by setting `MENTORAX_MYSQL_MIGRATION_TEST_URL`, `MENTORAX_MYSQL_MIGRATION_TEST_USERNAME`, and `MENTORAX_MYSQL_MIGRATION_TEST_PASSWORD`, then selecting `FreshMySqlMigrationTest`. It refuses a schema that already contains tables.

## Checksum or history conflicts

Do not edit an applied migration or delete Flyway history rows to silence a conflict. Stop deployment, back up the database, run Flyway `info`/`validate`, and compare the reported checksum and description with the checked-in migration. Use Flyway `repair` only for a known failed migration after verifying the resulting schema. The application only auto-repairs the narrowly recognized case where historical V2 failed immediately after the old normalized V1 (confirmed by the unchanged V1 column layout); all other failed or unrecognized histories are left for explicit review.
