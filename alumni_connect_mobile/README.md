# Alumni Connect mobile

Flutter client for the Spring Boot service in `../alumni-connect`.

Run with a reachable server URL:

```sh
flutter pub get
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

The default URL targets the Android emulator host. For a physical device, supply
the machine's LAN address over HTTPS or a trusted development network.

Implemented contract paths are derived directly from the backend controllers:
`/login`, `/signup`, `/users`, `/connections`, `/events`, `/notifications`,
`/conversations`, `/messages/conversation`, and admin alumni approval.

Known backend gap: `WebSocketConfig` declares the SockJS endpoint and inbound
`/app/chat` mapping but not the subscription destinations for delivered chat or
notifications. Live subscriptions are therefore deliberately not fabricated.
