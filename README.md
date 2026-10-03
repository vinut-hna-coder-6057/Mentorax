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

## Email verification status

Email OTP verification has been removed from signup and signin for the current
testing phase. Signup does not send verification email, and any account with
valid credentials and an approved status can sign in regardless of the legacy
`email_verified` database column. Alumni continue to require administrator
approval. Password-reset OTP and other application email notifications remain
separate and continue to use the configured Resend integration.

This is a temporary product decision, not a production-ready account-security
policy. Restore and test email ownership verification before relying on
verified email addresses for production security decisions. No migration or
bulk update is required; existing verification data and Flyway history are
left unchanged.

## Running the Flutter application

```bash
cd alumni_connect_mobile
flutter pub get
flutter run
```

The Flutter application is the current client. The former Angular frontend is
not part of the project.
