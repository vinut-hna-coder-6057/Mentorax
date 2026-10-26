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
  Stream<RealtimeSessionEvent<ChatMessage>> get messageEvents =>
      _messageController.stream.map(
        (message) => RealtimeSessionEvent(0, 0, message),
      );
  @override
  Stream<RealtimeState> get states => _stateController.stream;
  @override
  Stream<RealtimeFailure> get errors => _errorController.stream;
  @override
  RealtimeState get current => RealtimeState.connected;

  @override
  bool isCurrentSession(int sessionVersion, [int? connectionVersion]) => true;

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
          'displayName': 'Alice Alumni',
          'profileImage': 'https://example.test/alice.png',
          'participantRole': 'ALUMNI',
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
    expect(conversations.single.displayName, 'Alice Alumni');
    expect(conversations.single.profileImage, 'https://example.test/alice.png');
    expect(conversations.single.participantRole, UserRole.alumni);
    expect(conversations.single.latestMessage, 'Hello');
  });

  test('conversation role filter is sent to the backend', () async {
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test'));
    final adapter = DioAdapter(dio: dio);
    dio.httpClientAdapter = adapter;
    adapter.onGet(
      '/conversations',
      (server) => server.reply(200, []),
      queryParameters: {
        'page': 0,
        'size': 50,
        'participantRole': 'ALUMNI',
      },
    );
    final repository = AppRepository(
      ApiClient(SecureStorageService(const FlutterSecureStorage()), dio: dio),
    );

    final conversations = await repository.conversations(
      participantRole: UserRole.alumni,
    );

    expect(conversations, isEmpty);
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

  testWidgets('alumni can review and edit assistant draft without sending it',
      (tester) async {
    Map<String, dynamic>? copiedClipboardData;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'Clipboard.setData') {
          copiedClipboardData = Map<String, dynamic>.from(
            call.arguments as Map,
          );
        }
        return null;
      },
    );
    addTearDown(() {
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });
    const me = User(
      id: 2,
      name: 'Alumni',
      email: 'alumni@example.test',
      role: UserRole.alumni,
      status: 'APPROVED',
    );
    const studentEmail = 'student@example.test';
    final dio = Dio(BaseOptions(
      baseUrl: 'https://api.example.test',
      responseType: ResponseType.plain,
    ));
    final adapter = DioAdapter(dio: dio);
    dio.httpClientAdapter = adapter;
    adapter.onGet(
      '/messages/conversation',
      (server) => server.reply(200, [
        {
          'id': 44,
          'senderEmail': studentEmail,
          'receiverEmail': me.email,
          'content': 'Could you help me prepare for my interview?',
          'timestamp': '2026-10-20T10:00:00',
          'conversationId': 12,
        }
      ]),
      queryParameters: {
        'sender': me.email,
        'receiver': studentEmail,
        'page': 0,
        'size': 50,
      },
    );
    final assistantSuccess = {
      'schemaVersion': 'mentorax-inbox-assistant-v1',
      'available': true,
      'category': 'INTERVIEW_PREPARATION',
      'categoryLabel': 'Interview preparation',
      'confidence': 'moderate',
      'uncertain': false,
      'categoryEvidence': ['interview'],
      'prioritySuggestion': 'normal',
      'urgencyKind': 'none',
      'urgencyEvidence': null,
      'deadlineEvidence': null,
      'summary': 'Could you help me prepare for my interview?',
      'missingInformation':
          'Review the original message for details that may need clarification.',
      'draft':
          'Hi, thanks for reaching out. Could you share any additional context that would help me understand your request?',
      'draftKind': 'deterministic_template',
      'modelMetadata': {
        'strategy':
            'TF-IDF category-description similarity plus deterministic urgency rules',
        'trainedForMentorax': false,
        'scoresAreCalibratedProbabilities': false,
        'transformer': 'not_configured',
      },
      'message': null,
    };
    adapter.onPost(
      '/messages/44/assistant',
      (server) => server.reply(200, {
        'schemaVersion': 'mentorax-inbox-assistant-v1',
        'available': false,
        'message': 'AI assistance is unavailable.',
      }),
    );
    final storage = SecureStorageService(const FlutterSecureStorage());
    final repository = AppRepository(ApiClient(storage, dio: dio));
    final realtime = _TestRealtimeService();
    addTearDown(realtime.dispose);
    await tester.pumpWidget(ProviderScope(
      overrides: [
        appRepositoryProvider.overrideWithValue(repository),
        realtimeServiceProvider.overrideWithValue(realtime),
        authProvider.overrideWith((ref) {
          final notifier = AuthNotifier(repository, storage, realtime);
          notifier.setUser(me);
          return notifier;
        }),
      ],
      child: const MaterialApp(home: ChatScreen(email: studentEmail)),
    ));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Inbox assistant'));
    await tester.pumpAndSettle();
    expect(find.text('AI assistance is unavailable.'), findsOneWidget);
    expect(find.text('Retry'), findsOneWidget);
    adapter.onPost(
      '/messages/44/assistant',
      (server) => server.reply(200, assistantSuccess),
    );
    await tester.tap(find.text('Retry'));
    await tester.pump();
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
    await tester.pumpAndSettle();
    expect(find.text('Category: Interview preparation'), findsOneWidget);
    expect(find.text('Confidence: moderate'), findsOneWidget);
    expect(find.text('Evidence: interview'), findsOneWidget);
    expect(find.text('Suggested priority: normal · none'), findsOneWidget);
    expect(
      find.text('Could you help me prepare for my interview?'),
      findsNWidgets(3),
    );
    expect(
        find.text('Suggested reply draft · review before use'), findsOneWidget);
    final draft = tester.widgetList<TextField>(find.byType(TextField)).last;
    await tester.enterText(find.byWidget(draft), 'Edited draft for review');
    await tester.tap(find.text('Copy draft'));
    await tester.pumpAndSettle();
    expect(realtime.sentMessages, isEmpty);
    expect(copiedClipboardData?['text'], 'Edited draft for review');
    expect(find.text('Draft copied. It was not sent.'), findsOneWidget);
  });
}
