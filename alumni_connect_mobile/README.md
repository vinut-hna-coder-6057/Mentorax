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
defines; values compiled into an app are public. The current
`com.example.alumni_connect_mobile`
application ID is still Flutter's placeholder and must be replaced with the
organization's chosen unique ID before publishing; it is intentionally not
guessed here.

Realtime messaging uses the same `API_BASE_URL` with the SockJS `/chat`
endpoint. The STOMP client converts HTTPS to secure WebSocket (`wss://`) and
sends the JWT in its STOMP `CONNECT` headers. SockJS transport setup is
unauthenticated at the HTTP layer; the backend validates the JWT before
accepting the STOMP connection.

Implemented contract paths are derived directly from the backend controllers:
`/login`, `/signup`, `/users`, `/connections`, `/events`, `/notifications`,
`/conversations`, `/messages/conversation`, and admin alumni approval.
