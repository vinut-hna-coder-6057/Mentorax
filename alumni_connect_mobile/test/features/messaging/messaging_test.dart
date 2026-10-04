import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/presentation.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

class _TestRealtimeService extends RealtimeService {
  final _messageController = StreamController<ChatMessage>.broadcast();
  final _stateController = StreamController<RealtimeState>.broadcast();
  final _errorController = StreamController<RealtimeFailure>.broadcast();
  final sentMessages = <ChatMessage>[];

  @override
  Stream<ChatMessage> get messages => _messageController.stream;
  @override
  Stream<RealtimeState> get states => _stateController.stream;
  @override
  Stream<RealtimeFailure> get errors => _errorController.stream;
  @override
  RealtimeState get current => RealtimeState.connected;

  @override
  bool send(ChatMessage message) {
    sentMessages.add(message);
    return true;
  }

  void confirm(ChatMessage message) => _messageController.add(message);
  void reject(RealtimeFailure failure) => _errorController.add(failure);

  @override
  Future<void> dispose() async {
    await _messageController.close();
    await _stateController.close();
    await _errorController.close();
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const storageChannel =
      MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  test('chat message validation matches the backend 2000-character limit', () {
    expect(isValidChatMessageContent('  '), isFalse);
    expect(isValidChatMessageContent('a' * 2000), isTrue);
    expect(isValidChatMessageContent('a' * 2001), isFalse);
  });

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

  testWidgets('chat draft clears only after server echo and survives rejection',
      (tester) async {
    const me = User(
      id: 1,
      name: 'Asha Student',
      email: 'asha@example.test',
      role: UserRole.student,
      status: 'APPROVED',
    );
    const otherEmail = 'mentor@example.test';
    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );
    final adapter = DioAdapter(dio: dio);
    adapter.onGet(
      '/messages/conversation',
      (server) => server.reply(200, []),
      queryParameters: {
        'sender': me.email,
        'receiver': otherEmail,
        'page': 0,
        'size': 50,
      },
    );
    final storage = SecureStorageService(const FlutterSecureStorage());
    final repository = AppRepository(ApiClient(storage, dio: dio));
    final realtime = _TestRealtimeService();
    addTearDown(realtime.dispose);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appRepositoryProvider.overrideWithValue(repository),
          realtimeServiceProvider.overrideWithValue(realtime),
          authProvider.overrideWith((ref) {
            final notifier = AuthNotifier(repository, storage, realtime);
            notifier.setUser(me);
            return notifier;
          }),
        ],
        child: const MaterialApp(
          home: ChatScreen(email: otherEmail),
        ),
      ),
    );
    await tester.pumpAndSettle();

    final input = find.byType(TextField);
    await tester.enterText(input, 'Please keep this draft');
    await tester.tap(find.byTooltip('Send message'));
    await tester.pump();

    expect(realtime.sentMessages, hasLength(1));
    expect(tester.widget<TextField>(input).controller!.text,
        'Please keep this draft');
    expect(tester.widget<TextField>(input).readOnly, isTrue);

    realtime.confirm(
      const ChatMessage(
        id: 1,
        senderEmail: 'asha@example.test',
        receiverEmail: otherEmail,
        content: 'Please keep this draft',
        timestamp: '2026-10-04T12:00:00',
      ),
    );
    await tester.pumpAndSettle();

    expect(tester.widget<TextField>(input).controller!.text, isEmpty);
    expect(find.text('Please keep this draft'), findsOneWidget);

    await tester.enterText(input, 'Keep this one after rejection');
    await tester.tap(find.byTooltip('Send message'));
    await tester.pump();
    realtime.reject(RealtimeFailure.send);
    await tester.pumpAndSettle();

    expect(tester.widget<TextField>(input).controller!.text,
        'Keep this one after rejection');
    expect(
      find.text('Message could not be sent. Your draft was kept.'),
      findsOneWidget,
    );
  });
}
