# Database migration notes

The application now creates the normalized tables below through Flyway migration `V1__create_alumni_connect_schema.sql` for new MySQL environments. Hibernate is configured with `ddl-auto=validate` and must not modify the production schema.

- `users` remains the authentication/account table.
- `student_profiles` and `alumni_profiles` use `user_id` as both primary key and foreign key.
- `skills` and `user_skills` model reusable skills and user proficiency.
- `events` retains the legacy API creator email and also stores `created_by_id`.
- `event_registrations` stores `event_id` and `user_id`, with a unique `(event_id, user_id)` constraint.
- `connections` stores requester and receiver foreign keys.
- `conversations` and `conversation_participants` model chat threads and membership.
- `messages` stores `conversation_id` and `sender_id`; legacy email fields remain for current Angular payload compatibility.
- `notifications` stores `recipient_id` and optional `actor_id`; the legacy email is retained for compatibility.
- `otps` stores `code_hash`, purpose, expiry, attempt count, and consumption time. Plaintext OTP values are not persisted.

## Existing database data

The initial migration is intended for a new `alumni_connect` database. It does not safely transform an existing schema or existing email-based rows by itself. Before using an existing database, take a backup and perform a reviewed migration in this order:

1. Create the new tables and nullable foreign-key columns.
2. Populate `student_profiles` and `alumni_profiles` from role-specific columns in `users`.
3. Resolve `events.created_by` emails to `users.id` and populate `created_by_id`.
4. Resolve event registration emails to `users.id`; reject or quarantine registrations whose user no longer exists.
5. Group each distinct message sender/receiver pair into one conversation, insert both participants, and populate message sender/conversation IDs.
6. Resolve notification and OTP emails to `users.id`; quarantine orphaned rows.
7. Backfill OTP rows only when a secure hash can be generated. Existing plaintext OTPs should be invalidated rather than copied.
8. Validate foreign-key counts and duplicate `(event_id, user_id)` rows before making new columns non-null.

Do not drop the legacy compatibility columns until the Angular client has been migrated to ID-based payloads and the backfill has been verified.
