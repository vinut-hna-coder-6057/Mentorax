# Database migrations

Flyway is enabled for normal application startup, and Hibernate uses `ddl-auto=validate`. Hibernate checks the schema; it does not create or update production tables.

## Migration routing

`FlywayMigrationConfiguration` inspects only the configured schema metadata and Flyway history before selecting locations:

| Detected database | Flyway locations | Action |
| --- | --- | --- |
| Empty schema | `db/fresh`, `db/shared` | Run the complete current schema baseline in `db/fresh/V1__create_current_schema.sql`. |
| Historical legacy chain (baseline at version 1, or successful legacy V2+) | `db/migration`, `db/shared` | Keep the original V1–V7 migration identities and checksums; apply pending legacy-chain migrations. |
| Existing normalized V1 baseline (`Existing normalized V1 baseline`) | `db/normalized`, `db/shared` | Run V2 to backfill profile data and add the current hardening. |
| Pre-Flyway legacy schema with `users.password` | `db/migration`, `db/shared` | Baseline at version 1, then run the legacy conversion and hardening migrations. |
| Unknown or inconsistent schema/history | None | Startup stops before migration; inspect and resolve the history explicitly. |

The SQL V1 history entry `create alumni connect schema` is shared by more than one historical route. If it appears without a successful chain-identifying migration, startup treats it as ambiguous and stops. The one recognized failed-V2 recovery is accepted only when the exact history rows and current normalized schema shape are verified; all other failed histories remain untouched and block startup.

When a Flyway history table exists, the detected history is authoritative. An explicit `spring.flyway.locations` override must match the detected chain or startup fails before Flyway validation; it cannot silently redirect an existing database to another chain. For a database without Flyway history, an explicit locations override remains supported.

The repository’s local MySQL 8.0.46 history was inspected during this change. It contains Flyway’s version-1 baseline marker and successful V2–V6 rows; V7 is pending. That database uses the historical legacy chain. The old migration files were therefore left byte-for-byte unchanged. `db/normalized/V1__create_alumni_connect_schema.sql` is a byte-identical copy of historical V1 so a database that actually applied V1 can retain its checksum.

The fresh V1 includes the normalized schema plus the conversation-pair and canonical-connection constraints currently supplied by legacy V5 and V7. V1 already includes the `email_verified` column and `messages.conversation_id` foreign key; those operations are not repeated in the fresh baseline. Profile data backfill is run only for databases that already contain user rows. Shared V12 marks accounts that existed before the email-verification flow as verified so the new requirement does not lock out existing users; accounts created after deployment remain unverified until they complete the flow. Shared V13 adds nullable `events.max_capacity`; `NULL` means unlimited for existing and newly created events that omit a capacity. Shared V14 adds the `(role, status, id)` index used by bounded keyset scans of eligible alumni.

Shared V23 adds a nullable `experience_claim_processing.lease_token`. A token is replaced atomically on every lease acquisition and is checked alongside attempt and evidence-version identity before a worker can process or record failure. Existing rows remain valid with a null token until acquired. The migration is additive and forward-only; do not remove the column while token-aware application instances are active.

Shared V24 adds `knowledge_contributions`, immutable `knowledge_contribution_versions`, bounded typed `knowledge_contribution_entries`, and append-only `knowledge_contribution_audit`. It adds no changes to existing verification tables or user/profile columns. Version snapshots are retained; a published edit creates a new current draft version while the prior published pointer stays visible until republished. Either consent scope can be revoked immediately because student and future AI eligibility queries join the current published version and recheck the owner’s current `ALUMNI`/`APPROVED` status. No retrieval or RAG endpoint is introduced in this milestone.

Shared V25 adds companion conversations/messages and source-reference rows, privacy-preserving unanswered-topic groups, owned roadmaps/items/source references, and freshness interval/reminder columns on contributions. These tables contain only authenticated student-owned conversation history, one normalized keyword group row per student/topic, and roadmap references to contribution ID/version. Source-dependent conversation text and source metadata are suppressed if the referenced version is no longer AI-eligible. Freshness reminders are claimed with a conditional update in the same transaction as writing an existing in-app notification; if the transaction fails, the due reminder remains retryable. Freshness intervals are limited to 90 or 180 days. The migration is additive and does not rewrite the prior V1–V24 history.

Shared V26 adds the shared Knowledge Companion answer cache, exact contribution-version references, and expiring MySQL generation leases. Cache rows contain only a conservative intent label, a grounded answer, provider/prompt versions, and approved contribution/version IDs; they have no original question text, user, or conversation identifier. The question/context key is a one-way SHA-256 digest. Reads recheck current published consent, alumni approval, exact version, and freshness confirmation. The stateless answer endpoint may use the cache; private conversation turns always bypass it. Invalidated and expired rows remain explicitly marked until replaced or maintenance removes them.

The cache accepts only allowlisted preparation/roadmap/general-resume intents with no personal markers, direct identifiers, verification request, or time-sensitive opening/deadline query. Its SHA-256 key includes canonical intent/topic, country and cycle when present, provider/model/prompt/cache versions, and the exact retrieved contribution versions and company/role context. It is not a semantic cache. Miss coordination uses a MySQL unique-key lease with a 60-second default expiry and a bounded 22-second wait; waiters retry lease acquisition so abandoned/expired leases can be recovered. If a generation exceeds the bounded wait, the waiting request preserves availability by generating without storing, so duplicate provider work remains possible in that exceptional case. Cache failures fail open to the existing grounded flow. TTL defaults are 30 days for stable guidance, 14 days for general preparation, 7 days for company-specific guidance, and 3 days for cycle-specific guidance. Exact provider token usage is unavailable; the cache exports a separately named completion-token estimate based on answer characters divided by four.

Future semantic reuse is intentionally out of scope. If evaluated later, embeddings must be versioned and similarity thresholds tested against false matches, with strict company/role/region/cycle guards, the same personal-query bypass, source revalidation, confidence-based fallback, and measured false-positive reporting. No embedding dependency or service is part of V26.

Keep applied migration files in `db/migration` immutable. Because the fresh and normalized histories use separate Flyway locations, changes added after their baselines must also be represented in those routed chains. The password-reset and alumni-status changes use V8/V9 for the legacy history, V2/V3 for the fresh history, and V3/V4 for the normalized history. Shared V12 applies the existing-account email-verification backfill, V13 adds event registration capacity, and V14 adds the approved-alumni lookup index to all supported histories.

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

The backend CI job provisions two disposable MySQL 8 schemas and explicitly runs `FreshMySqlMigrationTest` before the complete backend suite. The test class verifies the automatic fresh route from an empty schema and recovery of the recognized normalized-V1 history, including their shared V10–V14 migrations. Spring Boot starts with `ddl-auto=validate`; assertions cover the complete Flyway history, the recorded fresh checksums, required tables/columns, the outbox and approved-alumni indexes, event-capacity check constraint, foreign keys, and unique indexes. CI fails if either schema's URL or credentials are missing, rather than silently treating these real-MySQL tests as optional.

CI also provisions a separate disposable MySQL service on loopback port 3308 for `ExperienceClaimProcessingMySqlLifecycleTest`. It creates only the empty `mentorax_phase26_lifecycle_test` schema and enables the test explicitly; the test refuses a different host, port, schema or non-empty database. The test exercises Spring transactions and persistence with a controlled authenticated analysis responder, not the Python application. For a local run, set `PHASE26_PROCESSING_MYSQL_URL`, `PHASE26_PROCESSING_MYSQL_USERNAME`, and `PHASE26_PROCESSING_MYSQL_PASSWORD` for a fresh loopback schema on port 3308, create that empty fixed-name schema, then run `.\mvnw.cmd "-Dphase26.processing.mysql=true" "-Dtest=ExperienceClaimProcessingMySqlLifecycleTest" test`. Never reuse a populated schema or point it at production.

To run these tests locally, set `MENTORAX_MYSQL_MIGRATION_TEST_URL`, `MENTORAX_MYSQL_MIGRATION_TEST_USERNAME`, and `MENTORAX_MYSQL_MIGRATION_TEST_PASSWORD` to a disposable empty MySQL 8 schema; set the corresponding `MENTORAX_MYSQL_RECOVERY_TEST_*` variables to a second disposable empty schema. Both tests refuse to use a schema that already contains tables and fail when credentials are missing. Never point either test URL at production or a schema containing user data.

The test username is read from the supplied environment variables; it does not need to be `root`. Prefer a dedicated MySQL account with privileges limited to `mentorax_migration_test` and `mentorax_recovery_test`. The URLs must explicitly use MySQL on loopback port 3306 and the exact database name for that test; credentials and connection options must not be embedded in the URL. Missing credentials fail the tests rather than silently skipping them. The account must be able to read metadata and run Flyway DDL/history changes in those two schemas, but it does not need global privileges or permission to create or drop databases.

For the optional mentor ranking service, configure the same random secret in
`AI_MENTOR_API_TOKEN` (Spring Boot) and `MENTOR_RANKING_API_TOKEN` (Python).
This protects `/rank` and `/rank/batch` without placing credentials in request
URLs or source files.

## Render and Aiven runtime configuration

The current Spring Boot service is `https://mentorax-backend.onrender.com`; its
Python support service is separate at `https://mentorax.onrender.com`. Configure
the Render backend root directory as `alumni-connect` and deploy the checked-in
Dockerfile or the reviewed Maven build/start commands for the service. Do not
configure the Python URL as the mobile API base URL.

Set these values through Render's secret/environment settings; do not put
production values in repository files. **No live Render or Aiven settings were
read or changed during the repository audit.**

- `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` for the configured MySQL service.
  The application adds `sslMode=VERIFY_IDENTITY` when a MySQL URL has no TLS mode
  and refuses weaker or ambiguous TLS settings. Never put credentials in the
  JDBC URL. For Render, add the CA PEM downloaded from the actual provider service
  as the Secret File `aiven-mysql-ca.pem`, then set
  `DB_CA_PEM_PATH=/etc/secrets/aiven-mysql-ca.pem`. The backend validates the PEM
  CA and creates a private, temporary PKCS12 truststore at startup. This PEM
  setting takes precedence over the legacy direct truststore settings
  (`DB_CA_TRUSTSTORE_PATH`, `DB_CA_TRUSTSTORE_PASSWORD`, and optional
  `DB_CA_TRUSTSTORE_TYPE=PKCS12` or `JKS`), which remain available for local and
  CI use. Do not use the disposable CI CA in production.
- `JWT_SECRET` (at least 32 UTF-8 bytes).
- `CORS_ALLOWED_ORIGIN_PATTERNS` and `WEBSOCKET_ALLOWED_ORIGINS` (exact trusted
  HTTPS app origins; avoid broad wildcards in production).
- `RESEND_API_KEY` and `RESEND_FROM_EMAIL` for verification, password-reset,
  and event-registration messages.
- `EXPERIENCE_EVIDENCE_ENCRYPTION_KEY`, a stable base64-encoded 32-byte key,
  so previously encrypted evidence remains readable across restarts.
- `EXPERIENCE_ANALYSIS_API_TOKEN` on both services when evidence analysis is
  enabled. The Python endpoint fails closed if its token is absent.
- `AI_SERVICE_URL` and matching `AI_MENTOR_API_TOKEN` / `MENTOR_RANKING_API_TOKEN`
  on Spring/Python when mentor ranking is enabled. The Python token is required
  even when the supplied value is empty or missing; protected ranking returns
  unavailable rather than opening access.
- `MENTOR_ENVIRONMENT=production` and `MENTOR_API_DOCS_ENABLED=false` on Python.

Knowledge Companion stays deterministic without an external model credential.
Only if external processing of eligible alumni contribution excerpts is
approved, set `KNOWLEDGE_ANSWER_PROVIDER=openai`, `OPENAI_API_KEY`, and optionally
`KNOWLEDGE_OPENAI_MODEL` and `KNOWLEDGE_PROVIDER_TIMEOUT_MS`. Keep the provider
key only in Spring's secret configuration. No API key is required for startup
or tests, and there is no client-side provider key.

Email configuration is optional for application startup and for administrator
or already-active account signin. `RESEND_API_KEY` and `RESEND_FROM_EMAIL` are
required for new student/alumni verification codes, password-reset OTPs, and
event-registration email delivery. Use a sender/domain verified in Resend;
test-mode accounts may restrict recipients to an authorized address. Without
these variables, queued email delivery will be deferred until the outbox
message expires, and new self-registered accounts cannot complete verification.

The public readiness endpoint is `/actuator/health/readiness`. It reports readiness only while the application is accepting traffic and the configured database is available. Only the health actuator endpoint is exposed, and health details are not included in responses. Check `/actuator/health/liveness` and `/actuator/health/readiness` after a reviewed deployment; historical health results do not prove current service health.

## Checksum or history conflicts

Do not edit an applied migration or delete Flyway history rows to silence a conflict. Stop deployment, back up the database, run Flyway `info`/`validate`, and compare the reported checksum and description with the checked-in migration. Use Flyway `repair` only for a known failed migration after verifying the resulting schema. The application only auto-repairs the narrowly recognized case where historical V2 failed immediately after the old normalized V1 (confirmed by the unchanged V1 column layout); all other failed or unrecognized histories are left for explicit review.
