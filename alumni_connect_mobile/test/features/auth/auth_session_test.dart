import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

class FakeSecureStorageService extends SecureStorageService {
  FakeSecureStorageService([this.token]) : super(const FlutterSecureStorage());
  String? token;

  @override
  Future<String?> readToken() async => token;

  @override
  Future<void> writeToken(String value) async {
    token = value;
  }

  @override
  Future<void> clearAuth() async {
    token = null;
  }
}

class FakeRealtimeService extends RealtimeService {
  bool disconnected = false;
  String? connectedToken;

  @override
  void connect(String token) {
    connectedToken = token;
    disconnected = false;
  }

  @override
  void disconnect() {
    disconnected = true;
    connectedToken = null;
  }
}

String createTestJwt({
  String email = 'asha@example.com',
  String role = 'STUDENT',
}) {
  final payload = jsonEncode({'sub': email, 'role': role});
  final chunk = base64Url.encode(utf8.encode(payload)).replaceAll('=', '');
  return 'eyJhbGciOiJIUzI1NiJ9.$chunk.signature';
}

const testUser = User(
  id: 1,
  name: 'Asha Student',
  email: 'asha@example.com',
  role: UserRole.student,
  status: 'APPROVED',
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  group('Authentication and Session Behavior', () {
    test('protected API request receiving 401 triggers sessionExpiredProvider',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final client = ApiClient(
        FakeSecureStorageService(),
        dio: dio,
        onUnauthorized: () {
          container.read(sessionExpiredProvider.notifier).state = true;
        },
      );

      adapter.onGet('/users/1', (server) => server.reply(401, 'Unauthorized'));

      expect(container.read(sessionExpiredProvider), isFalse);

      await expectLater(
        client.get('/users/1', decode: (d) => d),
        throwsA(
          isA<ApiException>().having(
            (e) => e.kind,
            'kind',
            ApiErrorKind.unauthorized,
          ),
        ),
      );

      expect(container.read(sessionExpiredProvider), isTrue);
    });

    test(
        'public auth endpoints do not trigger onUnauthorized or sessionExpiredProvider on 401',
        () async {
      const publicPaths = [
        '/login',
        '/signup',
        '/forgot-password',
        '/verify-otp',
        '/reset-password',
      ];

      for (final path in publicPaths) {
        final dio = Dio(
          BaseOptions(
            baseUrl: 'https://api.example.test',
            responseType: ResponseType.plain,
          ),
        );
        final adapter = DioAdapter(dio: dio);
        var unauthorizedInvoked = false;
        final container = ProviderContainer();
        addTearDown(container.dispose);

        final client = ApiClient(
          FakeSecureStorageService(),
          dio: dio,
          onUnauthorized: () {
            unauthorizedInvoked = true;
            container.read(sessionExpiredProvider.notifier).state = true;
          },
        );

        adapter.onPost(
          path,
          (server) => server.reply(401, 'Unauthorized'),
          data: const {},
        );

        await expectLater(
          client.post(path, data: const {}, decode: (d) => d),
          throwsA(
            isA<ApiException>().having(
              (e) => e.kind,
              'kind',
              ApiErrorKind.unauthorized,
            ),
          ),
        );

        expect(
          unauthorizedInvoked,
          isFalse,
          reason: 'onUnauthorized must not be called for public route: $path',
        );
        expect(
          container.read(sessionExpiredProvider),
          isFalse,
          reason:
              'sessionExpiredProvider must remain false for public route: $path',
        );
      }
    });

    test(
        '401 session expiry triggers AuthNotifier.signOut via sessionExpiredProvider listener',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      final fakeStorage = FakeSecureStorageService();
      final fakeRealtime = FakeRealtimeService()..connect('initial-token');

      final container = ProviderContainer(
        overrides: [
          secureStorageProvider.overrideWithValue(fakeStorage),
          realtimeServiceProvider.overrideWithValue(fakeRealtime),
          apiClientProvider.overrideWith((ref) {
            return ApiClient(
              ref.watch(secureStorageProvider),
              dio: dio,
              onUnauthorized: () {
                ref.read(sessionExpiredProvider.notifier).state = true;
              },
            );
          }),
        ],
      );
      addTearDown(container.dispose);

      final authNotifier = container.read(authProvider.notifier);
      await fakeStorage.writeToken(createTestJwt());
      authNotifier.setUser(testUser);

      expect(container.read(authProvider).value, equals(testUser));
      expect(await fakeStorage.readToken(), isNotNull);
      expect(fakeRealtime.disconnected, isFalse);

      adapter.onGet('/users/1', (server) => server.reply(401, 'Unauthorized'));

      final client = container.read(apiClientProvider);
      await expectLater(
        client.get('/users/1', decode: (d) => d),
        throwsA(
          isA<ApiException>().having(
            (e) => e.kind,
            'kind',
            ApiErrorKind.unauthorized,
          ),
        ),
      );

      // Wait for the asynchronous signOut triggered by the Riverpod listener to finish
      await pumpEventQueue();

      expect(container.read(authProvider).value, isNull);
      expect(await fakeStorage.readToken(), isNull);
      expect(fakeRealtime.disconnected, isTrue);
      expect(container.read(sessionExpiredProvider), isFalse);
    });

    test(
        'transient restore failure preserves stored token and records error without clearing auth',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      final validToken = createTestJwt(
        email: 'asha@example.com',
        role: 'STUDENT',
      );
      final fakeStorage = FakeSecureStorageService(validToken);
      final fakeRealtime = FakeRealtimeService();

      final apiClient = ApiClient(fakeStorage, dio: dio);
      final repository = AppRepository(apiClient);

      adapter.onGet(
        '/users/email/asha%40example.com',
        (server) => server.reply(500, 'Internal Server Error'),
      );

      final notifier = AuthNotifier(repository, fakeStorage, fakeRealtime);

      AsyncValue<User?>? lastState;
      notifier.addListener((state) {
        lastState = state;
      });

      await notifier.restore();

      expect(lastState, isNotNull);
      expect(lastState!.hasError, isTrue);
      expect(await fakeStorage.readToken(), equals(validToken));
      expect(fakeRealtime.disconnected, isTrue);
    });

    test('pending approval login uses the existing approval-screen contract',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      adapter.onPost(
        '/login',
        (server) => server.reply(
          403,
          {'error': 'Account pending approval'},
        ),
        data: {
          'email': 'alumni@example.com',
          'password': 'valid-password',
          'role': 'ALUMNI',
        },
      );
      final fakeStorage = FakeSecureStorageService();
      final fakeRealtime = FakeRealtimeService();
      final repository = AppRepository(ApiClient(fakeStorage, dio: dio));
      final notifier = AuthNotifier(repository, fakeStorage, fakeRealtime);

      expect(
        await notifier.signIn(
          'alumni@example.com',
          'valid-password',
          UserRole.alumni,
        ),
        'WAIT_APPROVAL',
      );
      expect(await fakeStorage.readToken(), isNull);
      expect(notifier.state.value, isNull);
    });

    test('failed profile fetch after login clears newly stored token',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      final token = createTestJwt();
      adapter.onPost(
        '/login',
        (server) => server.reply(200, token),
        data: {
          'email': 'asha@example.com',
          'password': 'valid-password',
          'role': 'STUDENT',
        },
      );
      adapter.onGet(
        '/users/email/asha%40example.com',
        (server) => server.reply(
          500,
          {'error': 'Internal Server Error'},
        ),
      );
      final fakeStorage = FakeSecureStorageService();
      final fakeRealtime = FakeRealtimeService();
      final repository = AppRepository(ApiClient(fakeStorage, dio: dio));
      final notifier = AuthNotifier(repository, fakeStorage, fakeRealtime);

      final result = await notifier.signIn(
        'asha@example.com',
        'valid-password',
        UserRole.student,
      );

      expect(result, contains('temporarily unavailable'));
      expect(await fakeStorage.readToken(), isNull);
      expect(notifier.state.value, isNull);
      expect(fakeRealtime.disconnected, isTrue);
    });

    test(
        'signOut clears stored token, disconnects realtime, and resets auth state',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final validToken = createTestJwt();
      final fakeStorage = FakeSecureStorageService(validToken);
      final fakeRealtime = FakeRealtimeService()..connect(validToken);
      final repository = AppRepository(ApiClient(fakeStorage, dio: dio));

      final notifier = AuthNotifier(repository, fakeStorage, fakeRealtime);
      notifier.setUser(testUser);

      AsyncValue<User?>? lastState;
      notifier.addListener((state) {
        lastState = state;
      });

      expect(await fakeStorage.readToken(), equals(validToken));
      expect(lastState?.value, equals(testUser));
      expect(fakeRealtime.disconnected, isFalse);

      await notifier.signOut();

      expect(await fakeStorage.readToken(), isNull);
      expect(lastState?.value, isNull);
      expect(lastState?.hasError, isFalse);
      expect(fakeRealtime.disconnected, isTrue);
    });
  });
}
