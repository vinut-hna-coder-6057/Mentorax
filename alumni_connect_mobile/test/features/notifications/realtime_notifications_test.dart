import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

class _TestStorage extends SecureStorageService {
  _TestStorage() : super(const FlutterSecureStorage());

  @override
  Future<String?> readToken() async => null;
}

class _TestRealtimeService extends RealtimeService {
  final _controller = StreamController<NotificationItem>.broadcast();

  @override
  Stream<NotificationItem> get notifications => _controller.stream;

  void emit(NotificationItem notification) => _controller.add(notification);

  @override
  Future<void> dispose() => _controller.close();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const storageChannel =
      MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  test('incoming realtime notifications refresh list and unread count',
      () async {
    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );
    final adapter = DioAdapter(dio: dio);
    var notificationFetches = 0;
    var unreadFetches = 0;
    adapter.onGet(
      '/notifications',
      (server) => server.replyCallback(200, (_) {
        notificationFetches++;
        return [
          {
            'id': notificationFetches,
            'message': 'New activity',
            'read': false,
            'type': 'MESSAGE',
          }
        ];
      }),
      queryParameters: {'page': 0, 'size': 50},
    );
    adapter.onGet(
      '/notifications/unread',
      (server) => server.replyCallback(200, (_) => ++unreadFetches),
    );
    final storage = _TestStorage();
    final repository = AppRepository(ApiClient(storage, dio: dio));
    final realtime = _TestRealtimeService();
    final container = ProviderContainer(
      overrides: [
        appRepositoryProvider.overrideWithValue(repository),
        secureStorageProvider.overrideWithValue(storage),
        realtimeServiceProvider.overrideWithValue(realtime),
      ],
    );
    final authSubscription = container.listen(authProvider, (_, __) {});
    final notificationsSubscription =
        container.listen(notificationsProvider, (_, __) {});
    final unreadSubscription =
        container.listen(unreadNotificationCountProvider, (_, __) {});
    addTearDown(() async {
      authSubscription.close();
      notificationsSubscription.close();
      unreadSubscription.close();
      await realtime.dispose();
      container.dispose();
    });

    await container.read(notificationsProvider.future);
    await container.read(unreadNotificationCountProvider.future);
    expect(notificationFetches, 1);
    expect(unreadFetches, 1);

    realtime.emit(
      const NotificationItem(
        id: 2,
        message: 'A new message arrived',
        isRead: false,
        type: 'MESSAGE',
      ),
    );
    await pumpEventQueue();
    await container.read(notificationsProvider.future);
    await container.read(unreadNotificationCountProvider.future);

    expect(notificationFetches, 2);
    expect(unreadFetches, 2);
  });
}
