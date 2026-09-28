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

  const sampleRequester = {
    'id': 1,
    'name': 'Asha Student',
    'email': 'asha@example.com',
    'role': 'STUDENT',
    'status': 'APPROVED',
  };

  const sampleReceiver = {
    'id': 2,
    'name': 'Vikram Alumni',
    'email': 'vikram@example.com',
    'role': 'ALUMNI',
    'status': 'APPROVED',
    'company': 'Tech Corp',
    'jobRole': 'Software Engineer',
  };

  final sampleConnectionPending = {
    'id': 101,
    'status': 'PENDING',
    'requester': sampleRequester,
    'receiver': sampleReceiver,
  };

  final sampleConnectionAccepted = {
    'id': 101,
    'status': 'ACCEPTED',
    'requester': sampleRequester,
    'receiver': sampleReceiver,
  };

  final sampleConnectionRejected = {
    'id': 101,
    'status': 'REJECTED',
    'requester': sampleRequester,
    'receiver': sampleReceiver,
  };

  group('Connections API Contract', () {
    // ------------------------------------------------------------
    // TEST 1 — FETCH CONNECTIONS
    // ------------------------------------------------------------
    test('connections fetches list from GET /connections and maps models',
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
        '/connections',
        (server) => server.reply(
          200,
          [
            sampleConnectionPending,
            sampleConnectionAccepted,
          ],
        ),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final connections = await repository.connections();

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/connections');
      expect(sentRequest!.uri.host, 'api.example.test');
      expect(adapter.history, hasLength(1));

      expect(connections, hasLength(2));

      expect(connections[0].id, 101);
      expect(connections[0].status, 'PENDING');
      expect(connections[0].requester?.name, 'Asha Student');
      expect(connections[0].requester?.role, UserRole.student);
      expect(connections[0].receiver?.name, 'Vikram Alumni');
      expect(connections[0].receiver?.role, UserRole.alumni);
      expect(connections[0].receiver?.company, 'Tech Corp');

      expect(connections[1].id, 101);
      expect(connections[1].status, 'ACCEPTED');
    });

    // ------------------------------------------------------------
    // TEST 2 — SEND CONNECTION REQUEST
    // ------------------------------------------------------------
    test(
        'requestConnection sends POST to /connections/:id and returns connection item',
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

      adapter.onPost(
        '/connections/2',
        (server) => server.reply(200, sampleConnectionPending),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final result = await repository.requestConnection(2);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'POST');
      expect(sentRequest!.path, '/connections/2');
      expect(sentRequest!.data, isNull);
      expect(adapter.history, hasLength(1));

      expect(result.id, 101);
      expect(result.status, 'PENDING');
      expect(result.requester?.id, 1);
      expect(result.receiver?.id, 2);
    });

    // ------------------------------------------------------------
    // TEST 3 — ACCEPT CONNECTION REQUEST
    // ------------------------------------------------------------
    test(
        'respondConnection sends PUT to /connections/:id with ACCEPTED query param',
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
        '/connections/101',
        (server) => server.reply(200, sampleConnectionAccepted),
        queryParameters: {'status': 'ACCEPTED'},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final result = await repository.respondConnection(101, 'ACCEPTED');

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'PUT');
      expect(sentRequest!.path, '/connections/101');
      expect(sentRequest!.queryParameters, {'status': 'ACCEPTED'});
      expect(adapter.history, hasLength(1));

      expect(result.id, 101);
      expect(result.status, 'ACCEPTED');
    });

    // ------------------------------------------------------------
    // TEST 4 — REJECT CONNECTION REQUEST
    // ------------------------------------------------------------
    test(
        'respondConnection sends PUT to /connections/:id with REJECTED query param',
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
        '/connections/101',
        (server) => server.reply(200, sampleConnectionRejected),
        queryParameters: {'status': 'REJECTED'},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final result = await repository.respondConnection(101, 'REJECTED');

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'PUT');
      expect(sentRequest!.path, '/connections/101');
      expect(sentRequest!.queryParameters, {'status': 'REJECTED'});
      expect(adapter.history, hasLength(1));

      expect(result.id, 101);
      expect(result.status, 'REJECTED');
    });

    // ------------------------------------------------------------
    // ERROR CONTRACT TESTS (400, 404, 409)
    // ------------------------------------------------------------
    test('requestConnection handles 400 Bad Request when connecting to self',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPost(
        '/connections/1',
        (server) => server.reply(400, 'Cannot connect to yourself'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.requestConnection(1),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.validation)
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.message, 'message',
                  contains('Cannot connect to yourself')),
        ),
      );
    });

    test(
        'requestConnection handles 404 Not Found when target user does not exist',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPost(
        '/connections/999',
        (server) => server.reply(404, 'User not found'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.requestConnection(999),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.notFound)
              .having((e) => e.statusCode, 'statusCode', 404),
        ),
      );
    });

    test(
        'requestConnection handles 409 Conflict when connection already exists',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPost(
        '/connections/2',
        (server) => server.reply(409, 'Connection already exists'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.requestConnection(2),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.conflict)
              .having((e) => e.statusCode, 'statusCode', 409)
              .having((e) => e.message, 'message',
                  contains('Connection already exists')),
        ),
      );
    });
  });
}
