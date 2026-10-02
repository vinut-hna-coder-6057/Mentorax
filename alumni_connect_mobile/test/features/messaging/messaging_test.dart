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
  const storageChannel =
      MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  test('conversations requests the requested backend page and size', () async {
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test'));
    final adapter = DioAdapter(dio: dio);
    dio.httpClientAdapter = adapter;
    adapter.onGet(
      '/conversations',
      (server) => server.reply(200, [
        {
          'email': 'alice@example.test',
          'latestMessage': 'Hello',
          'timestamp': '2026-10-02T10:00:00',
        }
      ]),
      queryParameters: {'page': 2, 'size': 50},
    );
    final repository = AppRepository(
      ApiClient(SecureStorageService(const FlutterSecureStorage()), dio: dio),
    );

    final conversations = await repository.conversations(page: 2, size: 50);

    expect(conversations, hasLength(1));
    expect(conversations.single.email, 'alice@example.test');
    expect(conversations.single.latestMessage, 'Hello');
  });
}
