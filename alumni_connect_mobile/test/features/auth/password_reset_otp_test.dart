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

  test('password reset OTP still uses the dedicated verification endpoint',
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
    dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) {
        request = options;
        handler.next(options);
      },
    ));
    adapter.onPost(
      '/verify-otp',
      (server) => server.reply(200, {
        'resetToken': 'one-time-reset-token',
      }),
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

    final resetToken = await repository.verifyOtp(
      'student@example.com',
      '123456',
    );

    expect(resetToken, 'one-time-reset-token');
    expect(request!.data, {
      'email': 'student@example.com',
      'otp': '123456',
    });
    expect(adapter.history, hasLength(1));
  });
}
