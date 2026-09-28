import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  const sampleNotificationConnection = {
    'id': 1,
    'message': 'Your connection request with Vikram Alumni was accepted.',
    'read': false,
    'type': 'CONNECTION',
    'timestamp': '2026-09-28T09:30:00Z',
  };

  const sampleNotificationEvent = {
    'id': 2,
    'message': 'New event: Alumni Homecoming 2026 has been published.',
    'isRead': true,
    'type': 'EVENT',
    'timestamp': '2026-09-27T14:15:00Z',
  };

  const sampleNotificationMessage = {
    'id': 3,
    'message': 'Asha Student sent you a new message.',
    'read': false,
    'type': 'MESSAGE',
    'timestamp': '2026-09-28T11:00:00Z',
  };

  group('Notifications API Contract', () {
    // ------------------------------------------------------------
    // TEST 1 — FETCH NOTIFICATIONS LIST
    // ------------------------------------------------------------
    test(
        'notifications() fetches list from GET /notifications and maps NotificationItem models',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      RequestOptions? sentRequest;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            sentRequest = options;
            handler.next(options);
          },
        ),
      );

      adapter.onGet(
        '/notifications',
        (server) => server.reply(
          200,
          [
            sampleNotificationConnection,
            sampleNotificationEvent,
            sampleNotificationMessage,
          ],
        ),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final notifications = await repository.notifications();

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/notifications');
      expect(sentRequest!.queryParameters, isEmpty);
      expect(sentRequest!.uri.host, 'api.example.test');
      expect(adapter.history, hasLength(1));

      expect(notifications, isA<List<NotificationItem>>());
      expect(notifications, hasLength(3));

      // Notification 1: using 'read' key
      final n1 = notifications[0];
      expect(n1.id, 1);
      expect(n1.message,
          'Your connection request with Vikram Alumni was accepted.');
      expect(n1.isRead, isFalse);
      expect(n1.type, 'CONNECTION');
      expect(n1.timestamp, '2026-09-28T09:30:00Z');

      // Notification 2: using 'isRead' key
      final n2 = notifications[1];
      expect(n2.id, 2);
      expect(
          n2.message, 'New event: Alumni Homecoming 2026 has been published.');
      expect(n2.isRead, isTrue);
      expect(n2.type, 'EVENT');
      expect(n2.timestamp, '2026-09-27T14:15:00Z');

      // Notification 3: unread message
      final n3 = notifications[2];
      expect(n3.id, 3);
      expect(n3.message, 'Asha Student sent you a new message.');
      expect(n3.isRead, isFalse);
      expect(n3.type, 'MESSAGE');
      expect(n3.timestamp, '2026-09-28T11:00:00Z');
    });

    // ------------------------------------------------------------
    // TEST 2 — FETCH UNREAD NOTIFICATIONS COUNT (POSITIVE COUNT)
    // ------------------------------------------------------------
    test(
        'unreadCount() sends GET to /notifications/unread and returns positive integer count',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
        ),
      );
      final adapter = DioAdapter(dio: dio);
      RequestOptions? sentRequest;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            sentRequest = options;
            handler.next(options);
          },
        ),
      );

      adapter.onGet(
        '/notifications/unread',
        (server) => server.reply(200, 3),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final count = await repository.unreadCount();

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/notifications/unread');
      expect(sentRequest!.queryParameters, isEmpty);
      expect(adapter.history, hasLength(1));

      expect(count, 3);
    });

    // ------------------------------------------------------------
    // TEST 3 — FETCH UNREAD NOTIFICATIONS COUNT (ZERO COUNT)
    // ------------------------------------------------------------
    test(
        'unreadCount() sends GET to /notifications/unread and returns 0 when no unread notifications',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
        ),
      );
      final adapter = DioAdapter(dio: dio);
      RequestOptions? sentRequest;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            sentRequest = options;
            handler.next(options);
          },
        ),
      );

      adapter.onGet(
        '/notifications/unread',
        (server) => server.reply(200, 0),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final count = await repository.unreadCount();

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/notifications/unread');
      expect(sentRequest!.queryParameters, isEmpty);
      expect(adapter.history, hasLength(1));

      expect(count, 0);
    });

    // ------------------------------------------------------------
    // TEST 4 — MARK NOTIFICATION AS READ
    // ------------------------------------------------------------
    test('markRead sends PUT to /notifications/read/:id and succeeds',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      RequestOptions? sentRequest;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            sentRequest = options;
            handler.next(options);
          },
        ),
      );

      adapter.onPut(
        '/notifications/read/1',
        (server) => server.reply(200, ''),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await repository.markRead(1);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'PUT');
      expect(sentRequest!.path, '/notifications/read/1');
      expect(sentRequest!.data, isNull);
      expect(sentRequest!.queryParameters, isEmpty);
      expect(adapter.history, hasLength(1));
    });
  });

  group('Notifications API Contract Errors', () {
    // ------------------------------------------------------------
    // ERROR 1 — 401 UNAUTHORIZED ON NOTIFICATIONS
    // ------------------------------------------------------------
    test('notifications handles 401 Unauthorized when session is expired',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onGet(
        '/notifications',
        (server) => server.reply(401, 'Unauthorized'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.notifications(),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.unauthorized)
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.message, 'message',
                  contains('Your session has expired')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 2 — 401 UNAUTHORIZED ON UNREAD COUNT
    // ------------------------------------------------------------
    test('unreadCount handles 401 Unauthorized when session is expired',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onGet(
        '/notifications/unread',
        (server) => server.reply(401, 'Unauthorized'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.unreadCount(),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.unauthorized)
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.message, 'message',
                  contains('Your session has expired')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 3 — 404 NOT FOUND ON MARK READ
    // ------------------------------------------------------------
    test('markRead handles 404 Not Found when notification ID does not exist',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPut(
        '/notifications/read/999',
        (server) => server.reply(404, 'Notification not found'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.markRead(999),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.notFound)
              .having((e) => e.statusCode, 'statusCode', 404)
              .having((e) => e.message, 'message', contains('not found')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 4 — 403 FORBIDDEN ON MARK READ
    // ------------------------------------------------------------
    test(
        'markRead handles 403 Forbidden when user is not authorized to modify notification',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPut(
        '/notifications/read/10',
        (server) => server.reply(403, 'Forbidden'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.markRead(10),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.forbidden)
              .having((e) => e.statusCode, 'statusCode', 403)
              .having((e) => e.message, 'message', contains('permission')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 5 — 500 SERVER ERROR ON NOTIFICATIONS
    // ------------------------------------------------------------
    test('notifications handles 500 Server Error', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onGet(
        '/notifications',
        (server) => server.reply(500, 'Internal Server Error'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.notifications(),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.server)
              .having((e) => e.statusCode, 'statusCode', 500)
              .having((e) => e.message, 'message',
                  contains('temporarily unavailable')),
        ),
      );
    });
  });
}
