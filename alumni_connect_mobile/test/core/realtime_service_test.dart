import 'package:flutter_test/flutter_test.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

class _FakeStompClient implements RealtimeStompClient {
  _FakeStompClient(this.config);

  final StompConfig config;
  final subscriptions = <String, List<StompFrameCallback>>{};
  bool deactivated = false;
  bool _connected = false;

  @override
  bool get connected => _connected;

  @override
  void activate() {}

  @override
  void deactivate() {
    deactivated = true;
    _connected = false;
  }

  Future<void> open() async {
    await config.beforeConnect();
    _connected = true;
    config.onConnect(StompFrame(command: 'CONNECTED'));
  }

  @override
  void subscribe({
    required String destination,
    required StompFrameCallback callback,
    Map<String, String>? headers,
  }) {
    subscriptions.putIfAbsent(destination, () => []).add(callback);
  }

  @override
  void send({
    required String destination,
    Map<String, String>? headers,
    String? body,
  }) {}
}

void main() {
  group('RealtimeService configuration', () {
    test('builds the SockJS endpoint from the configured HTTPS API URL', () {
      final endpoint = RealtimeService.sockJsEndpointFor(
        'https://mentorax-production.up.railway.app/',
      );

      expect(endpoint, 'https://mentorax-production.up.railway.app/chat');

      final config = StompConfig.sockJS(url: endpoint);
      expect(config.connectUrl,
          startsWith('wss://mentorax-production.up.railway.app/chat/'));
      expect(config.connectUrl, endsWith('/websocket'));
    });

    test('preserves local Android emulator development URL', () {
      expect(
        RealtimeService.sockJsEndpointFor('http://10.0.2.2:8080'),
        'http://10.0.2.2:8080/chat',
      );

      final config = StompConfig.sockJS(
        url: RealtimeService.sockJsEndpointFor('http://10.0.2.2:8080'),
      );
      expect(config.connectUrl, startsWith('ws://10.0.2.2:8080/chat/'));
    });

    test('places the bearer token in the STOMP CONNECT headers', () {
      expect(
        RealtimeService.stompHeadersForToken('test.jwt.value'),
        {'Authorization': 'Bearer test.jwt.value'},
      );
    });

    test('rejects a missing token without creating a STOMP client', () async {
      var clientsCreated = 0;
      final service = RealtimeService(
        clientFactory: (_) {
          clientsCreated++;
          throw StateError('A client must not be created without a token.');
        },
      );
      final failures = <RealtimeFailure>[];
      final subscription = service.errors.listen(failures.add);
      addTearDown(() async {
        await subscription.cancel();
        await service.dispose();
      });

      service.connect('  ');
      await pumpEventQueue();

      expect(clientsCreated, 0);
      expect(service.current, RealtimeState.authenticationRejected);
      expect(failures, [RealtimeFailure.authentication]);
    });

    test('uses the destinations configured by the Spring message broker', () {
      expect(RealtimeService.messagesDestination, '/user/queue/messages');
      expect(
        RealtimeService.notificationsDestination,
        '/user/queue/notifications',
      );
      expect(RealtimeService.sendDestination, '/app/chat');
    });

    test('rejects non-HTTP API URLs before opening a socket', () {
      expect(
        () => RealtimeService.sockJsEndpointFor('file:///api'),
        throwsArgumentError,
      );
    });

    test('stale sessions and reconnect subscriptions cannot publish events',
        () async {
      final clients = <_FakeStompClient>[];
      final service = RealtimeService(
        clientFactory: (config) {
          final client = _FakeStompClient(config);
          clients.add(client);
          return client;
        },
      );
      final messages = <RealtimeSessionEvent<ChatMessage>>[];
      final notifications = <RealtimeSessionEvent<NotificationItem>>[];
      final messageSubscription = service.messageEvents.listen(messages.add);
      final notificationSubscription =
          service.notificationEvents.listen(notifications.add);
      addTearDown(() async {
        await messageSubscription.cancel();
        await notificationSubscription.cancel();
        await service.dispose();
      });

      service.connect('first-token');
      service.connect('first-token');
      expect(clients, hasLength(1));
      final firstClient = clients.single;
      await firstClient.open();
      firstClient.config.onConnect(StompFrame(command: 'CONNECTED'));
      expect(
          firstClient.subscriptions.values.fold(0, (n, x) => n + x.length), 2);

      final firstMessageCallback = firstClient
          .subscriptions[RealtimeService.messagesDestination]!.single;
      firstMessageCallback(_messageFrame(1));
      firstMessageCallback(_messageFrame(1));
      await pumpEventQueue();
      expect(messages, hasLength(1));

      firstClient.config.onWebSocketDone();
      await firstClient.open();
      expect(
          firstClient.subscriptions.values.fold(0, (n, x) => n + x.length), 4);
      final reconnectedCallback =
          firstClient.subscriptions[RealtimeService.messagesDestination]!.last;
      firstMessageCallback(_messageFrame(2));
      reconnectedCallback(_messageFrame(2));
      await pumpEventQueue();
      expect(messages.map((event) => event.value.id), [1, 2]);

      service.connect('replacement-token');
      expect(firstClient.deactivated, isTrue);
      final replacement = clients.last;
      await replacement.open();
      final replacementCallback = replacement
          .subscriptions[RealtimeService.messagesDestination]!.single;
      firstClient.subscriptions[RealtimeService.messagesDestination]!.last(
        _messageFrame(3),
      );
      replacementCallback(_messageFrame(3));
      final notificationCallback = replacement
          .subscriptions[RealtimeService.notificationsDestination]!.single;
      notificationCallback(_notificationFrame(9));
      notificationCallback(_notificationFrame(9));
      await pumpEventQueue();
      expect(messages.map((event) => event.value.id), [1, 2, 3]);
      expect(notifications, hasLength(1));

      service.disconnect();
      replacementCallback(_messageFrame(4));
      await pumpEventQueue();
      expect(messages.map((event) => event.value.id), [1, 2, 3]);
      expect(replacement.deactivated, isTrue);
    });
  });
}

StompFrame _messageFrame(int id) => StompFrame(
      command: 'MESSAGE',
      body:
          '{"id":$id,"senderEmail":"alice@example.test","receiverEmail":"bob@example.test","content":"hello","timestamp":"2026-10-11T10:00:00"}',
    );

StompFrame _notificationFrame(int id) => StompFrame(
      command: 'MESSAGE',
      body:
          '{"id":$id,"message":"hello","read":false,"type":"MESSAGE","timestamp":"2026-10-11T10:00:00"}',
    );
