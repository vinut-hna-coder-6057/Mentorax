import 'package:flutter_test/flutter_test.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

import 'package:alumni_connect_mobile/core/network/realtime_service.dart';

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
  });
}
