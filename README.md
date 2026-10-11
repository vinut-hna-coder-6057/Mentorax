# Mentorax

Mentorax is a mobile-first alumni and student networking application. It
supports verified account onboarding, alumni approval, professional
discoverability, connections, events, notifications, and realtime messaging.

## Project status

| Area | Status |
| --- | --- |
| Core authentication, verification, profiles, networking, events, notifications, and messaging | Implemented |
| Backend, Flutter, security, and database-migration tests | Present in the repository |
| GitHub Actions backend, Flutter, and Android build workflows | Configured |
| Production deployment | Not independently verified by this repository |
| Structured mentorship workflow and matching | Planned; not currently implemented |
| AI/LLM or machine-learning recommendations | Not implemented |

## User journeys

- **Student:** register, verify the email OTP, sign in, maintain a profile,
  discover approved community profiles, request connections, register for
  events, receive notifications, and message accepted connections.
- **Alumni:** register, verify the email OTP, wait for an administrator to
  approve the account, then use the student networking features.
- **Administrator:** sign in, review alumni approvals and event moderation, and
  access administrative user functions.
- **Password recovery:** request a separate password-reset OTP, verify it to
  receive a short-lived reset authorization, then set a new password. Email
  verification and password reset are distinct flows.

## Architecture

```text
Flutter mobile application
        │
        ├── REST / JSON ───────┐
        └── STOMP over SockJS ─┤
                               ▼
                     Spring Boot application
                               │
                       Controllers / DTOs
                               │
                           Services
                               │
                         Repositories
                               │
                    MySQL + Flyway migrations

Realtime path:
Flutter STOMP client → /chat SockJS endpoint → JWT-authenticated STOMP
CONNECT/SEND/SUBSCRIBE → application destinations and private user queues
```

### Backend

The Java 21 / Spring Boot backend uses Spring MVC, Spring Security, JWT,
Jakarta Bean Validation, Spring Data JPA/Hibernate, Flyway, MySQL, and Spring's
STOMP/WebSocket support. Business and ownership checks live in services and
derive the caller identity from the authenticated principal.

### Flutter

The `alumni_connect_mobile/` client uses Flutter, Riverpod, Dio, secure token
storage, GoRouter, and a STOMP client. Feature screens are grouped under
`lib/features/`; API access is centralized in the repository and API-client
layers.

### Realtime messaging

The Flutter client authenticates in the STOMP `CONNECT` frame with its JWT.
The backend checks the token and current account before accepting a connection,
validates private user-queue subscriptions, and rate-limits authenticated
message sends. SockJS transport setup is publicly reachable, but STOMP
operations require authentication. The current simple broker is in-memory and
is intended for a single application instance.

## Authentication and privacy

- Public signup creates an unverified student or alumni account and queues an
  email-verification OTP.
- OTPs are short-lived, hashed in the verification table, attempt-limited, and
  single-use. The outbox stores encrypted OTP payloads for asynchronous email
  delivery.
- Successful student email verification activates the account. Alumni remain
  pending until administrator approval. Login and protected requests require
  the applicable verified/approved account state.
- Password reset uses a distinct OTP purpose and a short-lived, single-use
  reset authorization token stored as a hash.
- Profile directory responses are limited to professional fields. Email,
  student roll number, section, and arbitrary location are not included in
  public directory/detail responses. Owners and administrators retain their
  full profile response. Connection responses share counterpart email only
  after the connection is accepted, as the existing chat client addresses
  recipients by email.
- HTTP and STOMP rate limits are bounded and process-local. They protect a
  single running instance; a shared limiter would be required before
  horizontal scaling.
- CORS and WebSocket origins are environment-configured. Do not use broad
  production origin patterns or commit credentials.

## Data and migrations

Hibernate runs with `ddl-auto=validate`; schema changes are handled by Flyway.
Migration routing chooses the fresh, normalized, or historical legacy chain
from the database's recognized shape/history and appends shared migrations.
Unknown or conflicting histories fail closed. Applied historical migrations
must remain unchanged; schema corrections belong in new migrations.

`alumni-connect/src/main/resources/DATABASE_MIGRATION.md` documents routing,
deployment checks, and MySQL migration testing. GitHub Actions provisions
disposable MySQL 8 schemas for real-MySQL migration tests. Those tests require
the configured disposable database and must not be pointed at databases with
application data.

## Local development

Prerequisites:

- Java 21
- MySQL 8
- Flutter SDK for mobile development

1. Create a local MySQL database and configure the backend variables shown in
   [`.env.example`](./.env.example). The example is a reference, not a file
   automatically loaded by Spring or the shell. Keep real values outside Git.
2. Start the backend:

   ```sh
   cd alumni-connect
   ./mvnw spring-boot:run
   ```

3. Start the Flutter client:

   ```sh
   cd alumni_connect_mobile
   flutter pub get
   flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
   ```

The emulator URL is for Android debug only. Profile/release Flutter builds
require an HTTPS API URL; see the [mobile README](./alumni_connect_mobile/README.md).

### Environment variables

Backend configuration is supplied through environment variables:

| Variable | Purpose |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL connection |
| `JWT_SECRET` | Signing and domain-separated email-outbox encryption key material; use a high-entropy secret of at least 32 bytes |
| `JWT_EXPIRATION_MS` | Optional token lifetime |
| `CORS_ALLOWED_ORIGIN_PATTERNS`, `WEBSOCKET_ALLOWED_ORIGINS` | Explicit trusted web origins |
| `RESEND_API_KEY`, `RESEND_FROM_EMAIL` | Email verification, password-reset, and event email delivery; use a verified sender |
| `RATE_LIMIT_LOGIN_IP`, `RATE_LIMIT_LOGIN_EMAIL`, `RATE_LIMIT_OTP_IP`, `RATE_LIMIT_OTP_EMAIL`, `RATE_LIMIT_WINDOW_MS` | HTTP login/OTP rate limits |
| `app.websocket.rate-limit.messages`, `app.websocket.rate-limit.window-ms` | Optional STOMP send limits (Spring property names) |
| `app.email-outbox.enabled`, `app.email-outbox.poll-delay-ms` | Optional outbox dispatcher settings |

Email delivery credentials are not needed merely to start the application, but
email verification and password-reset delivery require a functioning Resend
configuration. Without delivery, queued messages expire and self-registered
users cannot complete verification.

## Testing and CI

Backend:

```sh
cd alumni-connect
./mvnw test
```

Flutter:

```sh
cd alumni_connect_mobile
flutter analyze
flutter test
```

GitHub Actions runs backend tests with MySQL 8, Flutter analysis/tests, and a
debug Android APK build. The backend job explicitly runs
`FreshMySqlMigrationTest` against its disposable MySQL schemas before the full
backend suite. Local executions of that test class require separate empty
loopback MySQL test schemas configured with the `MENTORAX_MYSQL_MIGRATION_TEST_*`
and `MENTORAX_MYSQL_RECOVERY_TEST_*` variables; absent those settings, JUnit
skips those MySQL-only tests locally.

## API overview

The backend exposes REST endpoints for authentication and verification,
users/profiles, alumni administration, connections, events, notifications,
messages, and conversations. Realtime messaging is provided over STOMP at
`/chat`. Endpoint definitions and authorization rules are maintained in the
Spring controllers and security configuration.

## Deployment and limitations

The backend configuration supports a Railway-style deployment using externally
provided MySQL and secret variables. The repository documents a Railway run
configuration, but this README does not assert that a particular deployment is
currently live or production-ready. Review Flyway history and run migrations
against an explicitly selected database before deployment.

Known limitations:

- The STOMP simple broker and in-memory rate limiters are instance-local.
- Email delivery depends on Resend configuration.
- No structured mentorship-offering/request workflow or recommendation system
  is present. A future workflow should reuse existing profiles, notifications,
  connections, and messaging, and begin with deterministic explainable
  matching rather than an LLM.
- No benchmark figures are claimed; run a reproducible workload before drawing
  performance conclusions.
