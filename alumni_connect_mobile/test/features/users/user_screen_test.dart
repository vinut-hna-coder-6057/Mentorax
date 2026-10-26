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
import 'package:alumni_connect_mobile/features/users/user_screen.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

class _UserScreenRepository extends AppRepository {
  _UserScreenRepository()
      : super(
          ApiClient(
            SecureStorageService(const FlutterSecureStorage()),
            dio: Dio(BaseOptions(baseUrl: 'https://api.example.test')),
          ),
        );

  @override
  Future<User> user(int id) async => _profileUser;

  @override
  Future<List<ConnectionItem>> connections(
          {int page = 0, int size = 50}) async =>
      const [
        ConnectionItem(
          id: 1,
          status: 'ACCEPTED',
          requester: _student,
          receiver: _connectedUser,
        ),
      ];
}

const _student = User(
  id: 1,
  name: 'Student',
  email: 'student@example.test',
  role: UserRole.student,
  status: 'APPROVED',
);

const _connectedUser = User(
  id: 2,
  name: 'Alumnus',
  email: 'alumnus@example.test',
  role: UserRole.alumni,
  status: 'APPROVED',
);

const _profileUser = User(
  id: 2,
  name: 'Alumnus',
  email: '',
  role: UserRole.alumni,
  status: 'APPROVED',
);

Widget _testApp(_UserScreenRepository repository) {
  final router = GoRouter(
    initialLocation: '/user/2',
    routes: [
      GoRoute(
        path: '/user/:id',
        builder: (_, __) => const UserScreen(id: 2),
      ),
      GoRoute(
        path: '/chat/:email',
        builder: (_, state) => Scaffold(
          body: Center(
            child: Text('Chat for ${state.pathParameters['email']}'),
          ),
        ),
      ),
    ],
  );

  return ProviderScope(
    overrides: [
      appRepositoryProvider.overrideWithValue(repository),
      authProvider.overrideWith((ref) {
        final notifier = AuthNotifier(
          repository,
          ref.read(secureStorageProvider),
          RealtimeService(),
        );
        notifier.setUser(_student);
        return notifier;
      }),
    ],
    child: MaterialApp.router(routerConfig: router),
  );
}

Future<void> _expectMessageNavigation(
  WidgetTester tester, {
  required bool bottomButton,
}) async {
  await tester.pumpWidget(_testApp(_UserScreenRepository()));
  await tester.pumpAndSettle();

  expect(find.text('Alumnus'), findsOneWidget);
  final messageButtons = find.text('Message');
  expect(messageButtons, findsNWidgets(2));
  final button = bottomButton ? messageButtons.last : messageButtons.first;
  await tester.ensureVisible(button);
  await tester.pumpAndSettle();
  await tester.tap(button);
  await tester.pumpAndSettle();

  expect(find.text('Chat for alumnus@example.test'), findsOneWidget);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets("app-bar Message opens the connected user's chat",
      (tester) async {
    await _expectMessageNavigation(tester, bottomButton: false);
  });

  testWidgets("bottom Message opens the connected user's chat", (tester) async {
    await _expectMessageNavigation(tester, bottomButton: true);
  });
}
