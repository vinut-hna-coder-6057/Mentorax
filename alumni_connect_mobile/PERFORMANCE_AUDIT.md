# PERFORMANCE_AUDIT

## Executive Summary

The Flutter app does not show a broad pattern of speculative polling, global timer loops, or a large number of untracked network calls in the core provider layer. The main high-confidence concern is not blanket inefficiency but stale realtime-session callbacks: old STOMP lifecycle events can still fire after a newer connect/disconnect cycle. That can create overlapping socket state transitions, stale message or notification callbacks, and unnecessary reconnect churn when the user signs out or quickly changes accounts.

The codebase is also generally disciplined about user-scoped providers, auth invalidation, and screen cleanup. The most important fix is session-level guarding in the real-time service so older sockets cannot update the active session after a newer connection has already taken over.

## Confirmed Problems

| File | Function | Problem | Why it matters | Evidence | Severity | Proposed fix |
|---|---|---|---|---|---|---|
| lib/core/network/realtime_service.dart | RealtimeService.connect / disconnect / callback lifecycle | Stale socket callbacks could outlive the active session and manipulate the new socket state. | A previous STOMP connection could still emit `onConnect`, `onWebSocketDone`, or error callbacks after a newer connection was created. This can create mixed state, duplicate subscriptions, and reconnect churn. | `connect()` and `disconnect()` mutate the same shared service state; callback handlers read the shared `_client` and `_token` without checking whether they still belong to the active connection generation. | High | Guard each STOMP lifecycle callback with a monotonically increasing session version and ignore stale callbacks from old connections. |
| lib/core/network/realtime_service.dart | RealtimeService.connect | Connect/disconnect is not fully generation-aware; older connection callbacks can race with new connect requests. | In a logout/login or reconnect race, the old socket may still set state to `connected`/`reconnecting` even though the new session is already active. | The service stores a single `_client`, `_token`, and `_subscribed` flag but does not stamp lifecycle callbacks with a session id. | High | Use a per-session generation token and reject callbacks whose generation no longer matches the active session. |

## Potential Problems

| File | Function | Problem | Why it matters | Evidence | Severity | Proposed fix |
|---|---|---|---|---|---|---|
| lib/features/presentation.dart | ChatScreen.initState / _sub / _realtimeSub / _realtimeErrorSub | Conversation screen listens to realtime streams and keeps a local message list. | The list is bounded only by the app runtime; a long-lived conversation can grow without a deliberate cap. | `_messages` is an in-memory list with no max retained count; the history loader appends live and historical messages with deduplication, but no cap is enforced. | Medium | Keep the current implementation unless a concrete limit is required; if memory pressure is observed, cap retained history while keeping the latest N messages and preserving correctness. |
| lib/app/providers.dart | providers for events, connections, notifications, conversations | Provider invalidation is intentionally aggressive and can trigger refreshes after message or notification events. | This is acceptable for data freshness, but it can cause extra network traffic when many events occur in quick succession. | Many providers are invalidated on realtime callbacks or user actions. This is expected but should be watched under heavy activity. | Low | Keep invalidation boundaries narrow and only invalidate the specific set a user action changes. |
| lib/features/presentation.dart | _EventDetails.build / event list usage | Some large event detail images are loaded at full size by `Image.network`. | This is normal for rich media but can increase decode memory when images are large. | `Image.network` is used for event banners and profile images without explicit dimensions beyond a fixed height/width. | Low | Keep current UX unless profiling shows repeated large image decode pressure. |

## Not Problems

- No `Timer.periodic` loops were found in the main app flow.
- No long-lived polling loop was found in the core provider layer.
- No `StreamBuilder` or `FutureBuilder` misuse with repeated network calls was identified in the main authentication, events, and realtime paths.
- The app’s `realtimeServiceProvider` is a single provider-scoped service and is disposed through `ref.onDispose(service.dispose)`, which is the correct pattern.
- The logout path calls `AuthNotifier.signOut()`, which disconnects the realtime service and clears secure storage.
- Public auth routes are excluded from unauthorized callbacks, which prevents erroneous session expiry redirects during sign-in, sign-up, and reset flows.

## Network Request Findings

- File: lib/features/app_repository.dart
- Function: AppRepository.*
- Endpoint: `/login`, `/users/...`, `/events`, `/connections`, `/notifications`, `/conversations`, `/messages/conversation`
- Trigger: provider reads, screen requests, actions such as follow, approval, or message load
- How often: user-driven and provider-driven; pagination is supported on list screens
- Can execute during rebuild: Generally no; providers fetch once per dependency cycle and are invalidated only on deliberate refresh/invalidate calls
- Can execute repeatedly: Yes, when providers are invalidated or the user navigates to the same screen repeatedly
- Cancelled when screen disappears: Usually yes for the UI flow, though provider state can remain until invalidated or re-created
- Duplicate requests: possible if the same provider is invalidated from multiple listeners or screens
- Response retained: yes, in Riverpod state and local lists that remain live until invalidation or disposal
- Pagination: yes, in conversations, events, and directory screens
- Risk level: low-to-medium in normal usage; no broad evidence of polling loops or runaway network churn

## WebSocket Findings

- File: lib/core/network/realtime_service.dart
- Function: RealtimeService.connect / _onConnect / _onWebSocketDone / _onWebSocketError
- Endpoint: SockJS `/chat` endpoint generated from `AppEnvironment.baseUrl`
- Trigger: auth restore and successful login; depends on the runtime service lifecycle
- How often: once per active login session, plus reconnect cycles if the socket drops
- Can execute during rebuild: no
- Can execute repeatedly: yes, on connection drops or repeated sign-in flows
- Cancelled when screen disappears: yes, because the service is global and disposed on provider teardown; screen subscriptions are cancelled in `ChatScreen.dispose()`
- Duplicate requests: not from the same session when the service is properly guarded; the race risk is stale callbacks from older sessions
- Response retained: only transiently in the `StreamController` and local listener state; no large payload retention in the realtime service itself
- Pagination: not applicable
- Risk level: medium; main concern is stale callback/session races and reconnect churn after auth/session transitions.

## Chat Memory Findings

- File: lib/features/presentation.dart
- Function: ChatScreen._loadHistory, ChatScreen._loadOlderMessages, ChatScreen._messages
- Problem: message collections are stored in a local list and merged repeatedly without a hard cap.
- Why it matters: a user with a very long conversation could retain a large number of messages in memory. This is not necessarily a leak, but it is unbounded client-side state.
- Evidence: `_messages` is a `List<ChatMessage>`, loaded page-by-page via `/messages/conversation`, then merged with live messages from the realtime stream.
- Severity: Medium
- Proposed fix: keep the current implementation unless a concrete limit is required; if profiling shows memory growth, introduce a guarded upper bound (for example, retaining the most recent 500–1000 messages only when app memory becomes a real issue).

## Riverpod Findings

- File: lib/app/providers.dart
- Function: authProvider, conversationsProvider, eventsProvider, connectionsProvider, notificationsProvider, realtimeNotificationsProvider
- Problem: providers retain user- and session-scoped state, and some are refreshed on message or notification events.
- Why it matters: this is expected for a stateful app, but account switches require that previous user state is cleared and old streams are not kept alive.
- Evidence: `authProvider` listens for `sessionExpiredProvider`; `realtimeNotificationsProvider` invalidates notification providers when a message arrives; `signOut()` clears auth and disconnects realtime.
- Severity: Low-to-Medium
- Proposed fix: keep current provider boundaries; ensure stale session callbacks are blocked before they can mutate the active provider state.

## UI/Rebuild Findings

- File: lib/features/presentation.dart
- Function: various screen build methods and provider watches
- Problem: some screens watch multiple providers at once; this is expected for app state and not obviously excessive.
- Why it matters: broad rebuilds are not free, but there is no evidence of a repeated render loop or a widget tree that rebuilds continuously.
- Evidence: the app uses ConsumerWidget/ConsumerStatefulWidget and provider watches in a conventional way. There are no obvious `build()` side-effects or unbounded `setState` loops in the primary screens.
- Severity: Low
- Proposed fix: only optimize if profiling shows a hot screen is rebuilding excessively; do not change architecture without evidence.

## Image Findings

- File: lib/features/presentation.dart
- Function: `_EventDetails.build`, `EventsScreen.build`, `UserAvatar`, `Image.network`
- Problem: rich media is loaded over the network and displayed at fixed sizes.
- Why it matters: large remote images can increase decode memory, but they are used intentionally and do not show evidence of a feed-wide memory issue.
- Evidence: images are displayed with explicit height or constrained sizes, and error builders catch network issues.
- Severity: Low
- Proposed fix: continue to use standard image sizing and error handling; only tune cache or decode dimensions if performance profiling identifies a real hot spot.

## Timer/Subscription Findings

- File: lib/features/presentation.dart
- Function: ChatScreen.initState / dispose
- Problem: subscriptions are cancelled on screen disposal.
- Why it matters: this is the correct lifecycle pattern and prevents active chat listeners from surviving after navigation.
- Evidence: `_sub?.cancel()`, `_realtimeSub?.cancel()`, and `_realtimeErrorSub?.cancel()` are all in `dispose()`.
- Severity: Low
- Proposed fix: no change required beyond preserving the current lifecycle rules.

## Logout/Account-Switch Findings

- File: lib/app/providers.dart
- Function: authProvider, AuthNotifier.signOut
- Problem: the app supports a logout-driven disconnect and auth reset; the service can be reconnected on the next login.
- Why it matters: this is the correct flow for account switching so the previous session is not kept alive.
- Evidence: `AuthNotifier.signOut()` clears storage and calls `_realtime.disconnect()`. The provider listener on `sessionExpiredProvider` also triggers sign-out and resets the expiry flag.
- Severity: Low
- Proposed fix: keep this flow and add stale callback protection to prevent old sockets from updating the active session after the logout/login transition.

## Recommended Fixes

1. Realtime session guard: keep the active socket generation in `RealtimeService` and ignore stale callbacks from older sessions. This is the only confirmed high-confidence fix and the one implemented here.
2. Keep current provider invalidation logic, but make sure all lifecycle transitions remain session-aware.
3. Monitor long-lived chat lists under real usage before enforcing a message cap.
4. Do not add speculative caches or global polling; the app does not show evidence of broad polling loops.

## Implementation Summary

A safe, minimal fix was applied in `lib/core/network/realtime_service.dart` by introducing a per-connection session generation and checking it before mutating shared realtime state. This blocks stale STOMP lifecycle callbacks from an older session from updating the new connection state after a reconnect or logout/login sequence. The change does not alter the public API, does not weaken JWT handling, and does not change message semantics.
