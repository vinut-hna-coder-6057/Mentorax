# Alumni Connect

Alumni Connect is a mobile-first alumni networking project with a Flutter
frontend and a Spring Boot backend.

## Current architecture

```text
Flutter mobile application
          ↓
Spring Boot REST + STOMP/WebSocket backend
          ↓
MySQL with Flyway migrations
```

The backend is implemented in Java and uses:

- Spring Data JPA with Hibernate
- Spring Security
- JWT authentication
- Flyway database migrations
- STOMP/WebSocket realtime communication

## Project structure

```text
alumni_connect_mobile/  Flutter application
alumni-connect/         Spring Boot backend
```

Database migrations are stored in
`alumni-connect/src/main/resources/db/migration`.

## Prerequisites

- Flutter SDK
- Java 21
- MySQL
- Maven (or the included Maven wrapper)

## Running the backend

```bash
cd alumni-connect
./mvnw spring-boot:run
```

Configure the required database, Resend, and JWT settings using local
environment variables or the local application configuration. Do not commit
credentials or other secrets.

## Temporary email verification setting

Email OTP verification is temporarily disabled for signup and signin by
default (`EMAIL_VERIFICATION_REQUIRED=false`). Signup will not enqueue an
email-verification OTP, and approved accounts may sign in without setting
`email_verified`; password checks, role checks, account approval/rejection,
rate limits, and authorization remain enforced. Alumni still require approval.

The Flutter app follows the same temporary default. Restore verification
before treating the application as production-ready: set backend
`EMAIL_VERIFICATION_REQUIRED=true` and build Flutter with
`--dart-define=EMAIL_VERIFICATION_ENABLED=true`. Re-enable and verify OTP
delivery before relying on email ownership for account security. Existing
unverified accounts are not modified; while verification is disabled, an
unverified account can sign in only if its other account-status checks permit
it.

## Running the Flutter application

```bash
cd alumni_connect_mobile
flutter pub get
flutter run
```

The Flutter application is the current client. The former Angular frontend is
not part of the project.
