import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/messaging/inbox_assistant_screen.dart';
import 'package:alumni_connect_mobile/features/messaging/inbox_category.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

class _AssistantRepository extends AppRepository {
  _AssistantRepository({
    Map<int, List<Conversation>> pages = const {},
    Map<String, List<ChatMessage>> history = const {},
  })  : _pages = pages,
        _history = history,
        super(
          ApiClient(
            SecureStorageService(const FlutterSecureStorage()),
            dio: Dio(BaseOptions(baseUrl: 'https://api.example.test')),
          ),
        );

  final Map<int, List<Conversation>> _pages;
  final Map<String, List<ChatMessage>> _history;
  final List<int> requestedPages = [];
  final List<String> historyRequests = [];
  int assistantRequests = 0;

  @override
  Future<List<Conversation>> conversations({
    int page = 0,
    int size = 50,
    UserRole? participantRole,
  }) async {
    requestedPages.add(page);
    return _pages[page] ?? const [];
  }

  @override
  Future<List<ChatMessage>> messages(
    String sender,
    String receiver, {
    int page = 0,
    int size = 50,
  }) async {
    historyRequests.add(receiver);
    return _history[receiver] ?? const [];
  }

  @override
  Future<Map<String, dynamic>> inboxAssistant(int messageId) async {
    assistantRequests++;
    throw StateError('The assistant service must not be called for filtering');
  }
}

class _TestRealtimeService extends RealtimeService {
  @override
  RealtimeState get current => RealtimeState.disconnected;

  @override
  Stream<RealtimeState> get states => const Stream.empty();

  @override
  Stream<RealtimeFailure> get errors => const Stream.empty();

  @override
  Stream<RealtimeSessionEvent<ChatMessage>> get messageEvents =>
      const Stream.empty();

  @override
  bool isCurrentSession(int sessionVersion, [int? connectionVersion]) => true;

  @override
  Future<void> dispose() async {}
}

const _alumnus = User(
  id: 20,
  name: 'Asha Alumni',
  email: 'asha@example.test',
  role: UserRole.alumni,
  status: 'APPROVED',
);

Conversation _conversation(String email, String message) => Conversation(
      email: email,
      displayName: email.split('@').first,
      latestMessage: message,
      timestamp: '2026-10-20T12:00:00',
    );

Widget _assistantApp(_AssistantRepository repository) {
  final router = GoRouter(
    initialLocation: '/chat/inbox/assistant',
    routes: [
      GoRoute(
        path: '/chat/inbox/assistant',
        builder: (_, __) => const InboxAssistantScreen(),
      ),
      GoRoute(
        path: '/chat/:email',
        builder: (_, state) => Scaffold(
          body: Center(
            child: Text('Existing chat: ${state.pathParameters['email']}'),
          ),
        ),
      ),
    ],
  );
  return ProviderScope(
    overrides: [
      appRepositoryProvider.overrideWithValue(repository),
      realtimeServiceProvider.overrideWithValue(_TestRealtimeService()),
      authProvider.overrideWith((ref) {
        final notifier = AuthNotifier(
          ref.read(appRepositoryProvider),
          ref.read(secureStorageProvider),
          ref.read(realtimeServiceProvider),
        );
        notifier.setUser(_alumnus);
        return notifier;
      }),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

void main() {
  group('classifyConversationMessages', () {
    test('maps each supported topic to its category', () {
      expect(
        classifyConversationMessages(['Can you refer me for this role?']),
        InboxCategory.referralRequests,
      );
      expect(
        classifyConversationMessages(['I need career growth advice.']),
        InboxCategory.careerAdvice,
      );
      expect(
        classifyConversationMessages(['Would you be my mentor?']),
        InboxCategory.mentorship,
      );
      expect(
        classifyConversationMessages(['Our team is hiring for a new role.']),
        InboxCategory.jobOpportunities,
      );
      expect(
        classifyConversationMessages(['Can we do a mock interview?']),
        InboxCategory.interviewPreparation,
      );
    });

    test('referral takes precedence over a job or internship opportunity', () {
      expect(
        classifyConversationMessages([
          'Could you refer me for this internship opportunity?',
        ]),
        InboxCategory.referralRequests,
      );
      expect(
        classifyConversationMessages([
          'Could you help me get employee referrals for this job?',
        ]),
        InboxCategory.referralRequests,
      );
    });

    test('ignores greetings and repeated text, and defaults unclear messages',
        () {
      expect(
        classifyConversationMessages(['Hi', 'Hello', 'Hi']),
        InboxCategory.other,
      );
      expect(
        classifyConversationMessages([
          'Could you refer me for this role?',
          'Could you refer me for this role?',
        ]),
        InboxCategory.referralRequests,
      );
      expect(
        classifyConversationMessages(['Could you help me with this?']),
        InboxCategory.other,
      );
    });

    test('uses meaningful messages from the recent conversation', () {
      expect(
        classifyConversationMessages([
          'I am looking for help.',
          'Hi',
          'Could you refer me for the internship?',
        ]),
        InboxCategory.referralRequests,
      );
    });
  });

  testWidgets('categories filter, open the existing chat, and return',
      (tester) async {
    final referral = _conversation(
      'bala@example.test',
      'Could you refer me for this internship?',
    );
    final career = _conversation(
      'chandra@example.test',
      'I would like career growth advice.',
    );
    final repository = _AssistantRepository(
      pages: {
        0: [referral, career],
      },
    );
    await tester.pumpWidget(_assistantApp(repository));
    await tester.pumpAndSettle();

    expect(find.text('Referral Requests'), findsOneWidget);
    expect(find.text('Career Advice'), findsOneWidget);
    expect(find.text('Mentorship'), findsOneWidget);
    expect(find.text('Job Opportunities'), findsOneWidget);
    expect(find.text('Interview Preparation'), findsOneWidget);
    expect(find.text('Other'), findsOneWidget);
    expect(find.text('All Messages'), findsOneWidget);

    await tester.tap(find.text('Referral Requests'));
    await tester.pumpAndSettle();
    expect(find.text('bala'), findsOneWidget);
    expect(find.text('chandra'), findsNothing);
    expect(repository.assistantRequests, 0);

    await tester.tap(find.text('bala'));
    await tester.pumpAndSettle();
    expect(find.text('Existing chat: bala@example.test'), findsOneWidget);

    GoRouter.of(tester.element(find.text('Existing chat: bala@example.test')))
        .pop();
    await tester.pumpAndSettle();
    expect(find.text('bala'), findsOneWidget);
    await tester.tap(find.byTooltip('Back to categories'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('All Messages'));
    await tester.pumpAndSettle();
    expect(find.text('bala'), findsOneWidget);
    expect(find.text('chandra'), findsOneWidget);
  });

  testWidgets('uses recent history when the latest preview is only a greeting',
      (tester) async {
    final hello = _conversation('bala@example.test', 'Hi');
    final repository = _AssistantRepository(
      pages: {
        0: [hello],
      },
      history: {
        'bala@example.test': const [
          ChatMessage(
            senderEmail: 'bala@example.test',
            receiverEmail: 'asha@example.test',
            content: 'Hello',
          ),
          ChatMessage(
            senderEmail: 'asha@example.test',
            receiverEmail: 'bala@example.test',
            content: 'Can you refer me for this internship?',
          ),
        ],
      },
    );
    await tester.pumpWidget(_assistantApp(repository));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Referral Requests'));
    await tester.pumpAndSettle();

    expect(find.text('bala'), findsOneWidget);
    expect(repository.historyRequests, ['bala@example.test']);
    expect(repository.assistantRequests, 0);
  });

  testWidgets('loads later conversation pages when a category has no matches',
      (tester) async {
    final firstPage = List.generate(
      50,
      (index) => _conversation(
        'user$index@example.test',
        'Could you help me with this?',
      ),
    );
    final laterReferral =
        _conversation('later@example.test', 'Please refer me for this role.');
    final repository = _AssistantRepository(
      pages: {
        0: firstPage,
        1: [laterReferral],
      },
    );
    await tester.pumpWidget(_assistantApp(repository));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Referral Requests'));
    await tester.pumpAndSettle();
    expect(find.text('No conversations in this category yet'), findsOneWidget);

    await tester.tap(find.text('Load more conversations'));
    await tester.pumpAndSettle();

    expect(find.text('later'), findsOneWidget);
    expect(repository.requestedPages, contains(1));
  });

  testWidgets('shows an empty-category state for an empty inbox',
      (tester) async {
    await tester.pumpWidget(_assistantApp(_AssistantRepository()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Other'));
    await tester.pumpAndSettle();

    expect(find.text('No conversations yet'), findsOneWidget);
  });
}
