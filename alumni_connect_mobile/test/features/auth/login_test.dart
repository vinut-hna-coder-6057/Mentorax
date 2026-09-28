import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('login sends credentials and returns the backend auth token', () async {
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
    RequestOptions? sentRequest;
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          sentRequest = options;
          handler.next(options);
        },
      ),
    );

    final expectedClaims = {
      'sub': 'asha@example.com',
      'role': 'STUDENT',
    };
    final encodedClaims = base64Url
        .encode(utf8.encode(jsonEncode(expectedClaims)))
        .replaceAll('=', '');
    final backendToken = 'eyJhbGciOiJIUzI1NiJ9.$encodedClaims.signature';

    adapter.onPost(
      '/login',
      (server) => server.reply(200, backendToken),
      data: const {
        'email': 'asha@example.com',
        'password': 'correct-horse-battery-staple',
        'role': 'STUDENT',
      },
    );

    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    final token = await repository.login(
      'asha@example.com',
      'correct-horse-battery-staple',
      UserRole.student,
    );

    expect(sentRequest, isNotNull);
    expect(sentRequest!.method, 'POST');
    expect(sentRequest!.path, '/login');
    expect(sentRequest!.uri.host, 'api.example.test');
    expect(sentRequest!.data, {
      'email': 'asha@example.com',
      'password': 'correct-horse-battery-staple',
      'role': 'STUDENT',
    });
    expect(adapter.history, hasLength(1));

    expect(token, backendToken);
    final claims = jsonDecode(
      utf8.decode(base64Url.decode(base64Url.normalize(token.split('.')[1]))),
    ) as Map<String, dynamic>;
    expect(claims['sub'], 'asha@example.com');
    expect(userRole(claims['role'] as String), UserRole.student);
  });
}
