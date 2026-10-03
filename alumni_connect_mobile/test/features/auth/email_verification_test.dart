import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('verifyEmail sends email and OTP to /verify-email', () async {
    const storageChannel = MethodChannel(
      'plugins.it_nomads.com/flutter_secure_storage',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async => null);

    final dio = Dio(
      BaseOptions(
        baseUrl: 'http://localhost:8080',
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
      '/verify-email',
      (server) => server.reply(200, '{}'),
      data: const {
        'email': 'student@example.com',
        'otp': '123456',
      },
    );

    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    await repository.verifyEmail('student@example.com', '123456');

    expect(sentRequest, isNotNull);
    expect(sentRequest!.method, 'POST');
    expect(sentRequest!.path, '/verify-email');
    expect(sentRequest!.data, {
      'email': 'student@example.com',
      'otp': '123456',
    });
    expect(adapter.history, hasLength(1));
  });

  test('resendVerification posts only email to the public resend endpoint',
      () async {
    const storageChannel = MethodChannel(
      'plugins.it_nomads.com/flutter_secure_storage',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async => null);

    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );
    final adapter = DioAdapter(dio: dio);
    RequestOptions? request;
    dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
      request = options;
      handler.next(options);
    }));
    adapter.onPost(
      '/resend-verification',
      (server) => server.reply(
        200,
        'If this account needs verification, a code has been sent.',
      ),
      data: const {'email': 'student@example.com'},
    );
    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    await repository.resendVerification('student@example.com');

    expect(request!.path, '/resend-verification');
    expect(request!.data, {'email': 'student@example.com'});
    expect(adapter.history, hasLength(1));
  });

  test('resend provider failure is exposed as an API error', () async {
    const storageChannel = MethodChannel(
      'plugins.it_nomads.com/flutter_secure_storage',
    );
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(storageChannel, (call) async => null);
    final dio = Dio(BaseOptions(
      baseUrl: 'https://api.example.test',
      responseType: ResponseType.plain,
    ));
    final adapter = DioAdapter(dio: dio);
    adapter.onPost(
      '/resend-verification',
      (server) => server.reply(
        503,
        '{"error":"Email delivery is temporarily unavailable"}',
      ),
      data: const {'email': 'student@example.com'},
    );
    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    await expectLater(
      repository.resendVerification('student@example.com'),
      throwsA(
        isA<ApiException>()
            .having((error) => error.kind, 'kind', ApiErrorKind.server)
            .having((error) => error.statusCode, 'statusCode', 503)
            .having(
              (error) => error.message,
              'message',
              contains('temporarily unavailable'),
            ),
      ),
    );
  });
}
