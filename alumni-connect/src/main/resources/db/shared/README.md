# Shared Flyway migrations

This location is appended to each recognized migration chain for future migrations that apply to both fresh and upgraded schemas. Keep legacy and normalized baseline migrations in their dedicated locations; never edit versioned migrations that have already been applied.

Migrations are forward-only; Flyway does not automatically roll back an applied migration. Before applying a migration, back up the target database. If a migration must be reversed, add and review a new forward migration that restores the prior schema/data safely rather than editing or deleting the applied migration.

V21 adds `experience_claims.claim_type` with an `UNSPECIFIED` default and backfills existing claims. Keep V20 unchanged; it owns the evidence lifecycle schema.

V22 adds immutable, version-scoped structured extraction runs and normalized field, candidate, statement, provenance, and warning records. A composite foreign key binds each run and provenance row to the exact evidence ID/version. The response fingerprint prevents identical reprocessing from duplicating a run; a distinct successful response creates a new historical run. The Phase 2.4 decision processor reads only the latest successfully persisted extraction for each exact ACTIVE evidence ID/version; it does not use historical, SUPERSEDED, WITHDRAWN, or unpersisted analysis output. Extraction confidence supports evidence sufficiency only and is not an authenticity signal. V22 effects are forward-only; rollback requires a separately reviewed forward migration and must not delete historical extraction data.
