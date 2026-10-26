import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/app/router.dart';
import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/presentation.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';
import 'package:alumni_connect_mobile/shared/widgets/ui_components.dart';

class _InboxRepository extends AppRepository {
  _InboxRepository({
    List<Conversation> conversations = const [],
    this.failFirstConversationRequest = false,
    this.firstPageCompleter,
  })  : conversationsToReturn = conversations,
        super(
          ApiClient(
            SecureStorageService(const FlutterSecureStorage()),
            dio: Dio(BaseOptions(baseUrl: 'https://api.example.test')),
          ),
        );

  final List<Conversation> conversationsToReturn;
  final bool failFirstConversationRequest;
  final Completer<List<Conversation>>? firstPageCompleter;
  int conversationCalls = 0;
  final requestedRoles = <UserRole?>[];

  @override
  Future<List<Conversation>> conversations({
    int page = 0,
    int size = 50,
    UserRole? participantRole,
  }) {
    conversationCalls++;
    requestedRoles.add(participantRole);
    if (page == 0 && conversationCalls == 1 && firstPageCompleter != null) {
      return firstPageCompleter!.future;
    }
    if (page == 0 && conversationCalls == 1 && failFirstConversationRequest) {
      throw const ApiException(
        ApiErrorKind.network,
        'Unable to reach Alumni Connect. Check your connection and retry.',
      );
    }
    return Future.value(participantRole == null
        ? conversationsToReturn
        : conversationsToReturn
            .where((conversation) =>
                conversation.participantRole == participantRole)
            .toList());
  }

  @override
  Future<List<ConnectionItem>> connections({int page = 0, int size = 50}) =>
      Future.value(const []);

  @override
  Future<List<EventItem>> events({
    bool all = false,
    int page = 0,
    int size = 50,
  }) =>
      Future.value(const []);

  @override
  Future<int> unreadCount() => Future.value(0);

  @override
  Future<List<User>> pendingUsers({int page = 0, int size = 50}) =>
      Future.value(const []);

  @override
  Future<List<ChatMessage>> messages(
    String sender,
    String receiver, {
    int page = 0,
    int size = 50,
  }) =>
      Future.value(const []);
}

class _InboxRealtimeService extends RealtimeService {
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

const _approvedAlumnus = User(
  id: 10,
  name: 'Asha Alumni',
  email: 'asha@example.test',
  role: UserRole.alumni,
  status: 'APPROVED',
);

const _conversation = Conversation(
  email: 'bala@example.test',
  displayName: 'Bala Krish',
  latestMessage: 'Thanks for your advice',
  participantRole: UserRole.student,
  timestamp: '2026-10-02T10:00:00',
);

Widget _testApp(User user, _InboxRepository repository) => ProviderScope(
      overrides: [
        appRepositoryProvider.overrideWithValue(repository),
        realtimeServiceProvider.overrideWithValue(_InboxRealtimeService()),
        authProvider.overrideWith((ref) {
          final notifier = AuthNotifier(
            ref.read(appRepositoryProvider),
            ref.read(secureStorageProvider),
            ref.read(realtimeServiceProvider),
          );
          notifier.setUser(user);
          return notifier;
        }),
      ],
      child: Consumer(
        builder: (context, ref, _) => MaterialApp.router(
          routerConfig: ref.watch(routerProvider),
        ),
      ),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('approved alumni can open inbox, a conversation, and return',
      (tester) async {
    final repository = _InboxRepository(conversations: const [_conversation]);
    await tester.pumpWidget(_testApp(_approvedAlumnus, repository));
    await tester.pumpAndSettle();

    expect(find.text('Inbox'), findsOneWidget);
    await tester.tap(find.text('Inbox'));
    await tester.pumpAndSettle();

    expect(find.text('Bala Krish'), findsOneWidget);
    expect(find.text('bala@example.test'), findsOneWidget);
    expect(find.text('Thanks for your advice'), findsOneWidget);
    expect(find.textContaining('2026'), findsOneWidget);

    await tester.tap(find.text('Bala Krish'));
    await tester.pumpAndSettle();
    expect(find.text('bala@example.test'), findsOneWidget);

    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(find.text('Inbox'), findsOneWidget);
  });

  testWidgets('students cannot see or open the alumni inbox', (tester) async {
    const student = User(
      id: 11,
      name: 'Asha Student',
      email: 'student@example.test',
      role: UserRole.student,
      status: 'APPROVED',
    );
    await tester.pumpWidget(_testApp(student, _InboxRepository()));
    await tester.pumpAndSettle();

    expect(find.text('Inbox'), findsNothing);
    GoRouter.of(tester.element(find.text('Grow your network, Asha')))
        .go('/chat/inbox');
    await tester.pumpAndSettle();

    expect(find.text('Grow your network, Asha'), findsOneWidget);
    expect(find.text('Inbox'), findsNothing);
    GoRouter.of(tester.element(find.text('Grow your network, Asha')))
        .go('/chat/inbox/assistant');
    await tester.pumpAndSettle();

    expect(find.text('Inbox assistant'), findsNothing);
    expect(find.text('Grow your network, Asha'), findsOneWidget);
  });

  testWidgets('admins cannot see or open the alumni inbox', (tester) async {
    const admin = User(
      id: 13,
      name: 'Mentorax Admin',
      email: 'admin@example.test',
      role: UserRole.admin,
      status: 'APPROVED',
    );
    await tester.pumpWidget(_testApp(admin, _InboxRepository()));
    await tester.pumpAndSettle();

    expect(find.text('Inbox'), findsNothing);
    GoRouter.of(tester.element(find.text('Admin overview'))).go('/chat/inbox');
    await tester.pumpAndSettle();

    expect(find.text('Admin overview'), findsOneWidget);
    expect(find.text('Inbox'), findsNothing);
  });

  testWidgets('unapproved alumni cannot see or open the inbox', (tester) async {
    const pendingAlumnus = User(
      id: 12,
      name: 'Pending Alumni',
      email: 'pending@example.test',
      role: UserRole.alumni,
      status: 'PENDING',
    );
    await tester.pumpWidget(_testApp(pendingAlumnus, _InboxRepository()));
    await tester.pumpAndSettle();

    expect(find.text('Inbox'), findsNothing);
    GoRouter.of(tester.element(find.text('Welcome back, Pending')))
        .go('/chat/inbox');
    await tester.pumpAndSettle();

    expect(find.text('Inbox'), findsNothing);
  });

  testWidgets('inbox has an empty state and can be refreshed', (tester) async {
    final repository = _InboxRepository();
    await tester.pumpWidget(_testApp(_approvedAlumnus, repository));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Inbox'));
    await tester.pumpAndSettle();

    expect(find.text('No conversations yet'), findsOneWidget);
    expect(find.textContaining('connected person’s profile'), findsOneWidget);

    await tester.tap(find.byTooltip('Refresh inbox'));
    await tester.pumpAndSettle();
    expect(repository.conversationCalls, 2);
  });

  testWidgets('category selection displays only the selected participant role',
      (tester) async {
    const alumniConversation = Conversation(
      email: 'chandra@example.test',
      displayName: 'Chandra Alumni',
      latestMessage: 'Happy to help',
      participantRole: UserRole.alumni,
    );
    final repository = _InboxRepository(
      conversations: const [_conversation, alumniConversation],
    );
    await tester.pumpWidget(_testApp(_approvedAlumnus, repository));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Inbox'));
    await tester.pumpAndSettle();

    expect(find.text('Bala Krish'), findsOneWidget);
    expect(find.text('Chandra Alumni'), findsOneWidget);
    await tester.tap(find.text('Alumni'));
    await tester.pumpAndSettle();

    expect(repository.requestedRoles.last, UserRole.alumni);
    expect(find.text('Chandra Alumni'), findsOneWidget);
    expect(find.text('Bala Krish'), findsNothing);

    await tester.tap(find.text('All'));
    await tester.pumpAndSettle();
    expect(repository.requestedRoles, containsAll([null, UserRole.alumni]));
    expect(find.text('Bala Krish'), findsOneWidget);
    expect(find.text('Chandra Alumni'), findsOneWidget);
  });

  testWidgets('inbox displays loading and resolves when conversations load',
      (tester) async {
    final completer = Completer<List<Conversation>>();
    final repository = _InboxRepository(firstPageCompleter: completer);
    await tester.pumpWidget(_testApp(_approvedAlumnus, repository));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Inbox'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(repository.conversationCalls, 1);
    expect(find.byType(InboxScreen), findsOneWidget);
    expect(find.byType(LoadingState), findsOneWidget);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);

    completer.complete(const [_conversation]);
    await tester.pumpAndSettle();
    expect(find.text('Bala Krish'), findsOneWidget);
  });

  testWidgets('inbox error explains offline state and retry reloads',
      (tester) async {
    final repository = _InboxRepository(
      conversations: const [_conversation],
      failFirstConversationRequest: true,
    );
    await tester.pumpWidget(_testApp(_approvedAlumnus, repository));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Inbox'));
    await tester.pumpAndSettle();

    expect(find.textContaining('Check your connection'), findsOneWidget);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();

    expect(repository.conversationCalls, 2);
    expect(find.text('Bala Krish'), findsOneWidget);
  });
}
