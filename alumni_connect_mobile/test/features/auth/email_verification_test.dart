import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/network/api_client.dart';
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
}
