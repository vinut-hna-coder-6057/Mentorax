# Alumni Connect mobile

Flutter client for the Spring Boot service in `../alumni-connect`.

## Development

The Android debug build defaults to the Android emulator host at
`http://10.0.2.2:8080`. Override it with `--dart-define` when needed:

```sh
flutter pub get
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Debug Android permits cleartext traffic for local development. Main/release
Android manifests deny cleartext traffic.

## Android release

Configure a real release keystore locally; never commit the keystore or its
passwords. The Android `.gitignore` excludes both `android/key.properties` and
`.jks`/`.keystore` files. Create `android/key.properties` with:

```properties
storeFile=C:/secure/path/mentorax-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Alternatively provide `ANDROID_RELEASE_STORE_FILE`,
`ANDROID_RELEASE_STORE_PASSWORD`, `ANDROID_RELEASE_KEY_ALIAS`, and
`ANDROID_RELEASE_KEY_PASSWORD` as environment variables, or use the equivalent
`androidReleaseStoreFile`, `androidReleaseStorePassword`,
`androidReleaseKeyAlias`, and `androidReleaseKeyPassword` Gradle properties.
Release builds fail with a configuration message when a complete signing setup
and keystore file are missing; they never fall back to the debug key.

Supply the real production API base URL at build time:

```sh
flutter build apk --release --dart-define=API_BASE_URL=https://mentorax-production.up.railway.app
```

Profile/release API configuration requires this HTTPS URL; the app rejects a
missing or non-HTTPS API URL when its API configuration is initialized, and the
Android release manifest blocks cleartext HTTP. Do not put API secrets in Dart
defines; values compiled into an app are public. The Android application ID is
configured as `com.mentorax.app` for release publishing and must stay unique to
this app.

Realtime messaging uses the same `API_BASE_URL` with the SockJS `/chat`
endpoint. The STOMP client converts HTTPS to secure WebSocket (`wss://`) and
sends the JWT in its STOMP `CONNECT` headers. SockJS transport setup is
unauthenticated at the HTTP layer; the backend validates the JWT before
accepting the STOMP connection.

## Alumni inbox assistant

Approved alumni can open **Inbox assistant** from the existing Messages Inbox
to browse all messages or filter conversations by referral requests, career
advice, mentorship, job opportunities, interview preparation, or other/unclear
topics. These are local filters over the authenticated user's existing
conversations; selecting a result opens the existing chat. Categories use
deterministic message-content rules, with referral requests taking precedence
over job opportunities. Greetings and acknowledgements do not determine a
topic. When the latest preview is unclear, the app checks up to 20 recent
messages through the existing conversation API. Conversation pages can be
loaded progressively, so category filters can include matches beyond the first
page.

This category browser does not call the optional Python inbox-assistance
service. The per-message assistant remains a separate feature and can be
unavailable without preventing conversation browsing or replies.

Self-registration creates an unverified student or alumni account and opens
the email-code verification flow. A verified student can sign in; a verified
alumnus remains pending until an administrator approves the account. Existing
administrator sign-in and the separate password-reset OTP flow are unchanged.

Implemented contract paths are derived directly from the backend controllers:
`/login`, `/signup`, `/verify-email`, `/resend-verification`,
`/forgot-password`, `/verify-otp`, `/reset-password`, `/users`,
`/connections`, `/events`, `/notifications`, `/conversations`,
`/messages/conversation`, and admin alumni approval.
