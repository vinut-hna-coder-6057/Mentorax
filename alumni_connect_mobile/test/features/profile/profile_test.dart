import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';
import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/network/realtime_service.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/presentation.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';
//profile
class _ProfileRepository extends AppRepository {
  _ProfileRepository(super.api, {required this.failUpdate});

  final bool failUpdate;
  final submittedUsers = <User>[];

  @override
  Future<User> updateUser(User user) async {
    submittedUsers.add(user);
    if (failUpdate) {
      throw const ApiException(
        ApiErrorKind.server,
        'The service is temporarily unavailable. Try again shortly.',
      );
    }
    return user;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  const user = User(
    id: 1,
    name: 'Asha Student',
    email: 'asha@example.test',
    role: UserRole.student,
    status: 'APPROVED',
    bio: 'Current profile bio',
  );

  Future<void> pumpProfile(
    WidgetTester tester, {
    required _ProfileRepository repository,
  }) async {
    final storage = SecureStorageService(const FlutterSecureStorage());

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          appRepositoryProvider.overrideWithValue(repository),
          authProvider.overrideWith((ref) {
            final notifier = AuthNotifier(
              repository,
              storage,
              RealtimeService(),
            );
            notifier.setUser(user);
            return notifier;
          }),
        ],
        child: const MaterialApp(home: Scaffold(body: ProfileScreen())),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('profile loads authenticated user data and saves updates',
      (tester) async {
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test'));
    final repository = _ProfileRepository(
      ApiClient(SecureStorageService(const FlutterSecureStorage()), dio: dio),
      failUpdate: false,
    );
    await pumpProfile(
      tester,
      repository: repository,
    );

    expect(find.text('Asha Student'), findsNWidgets(2));
    expect(find.text('asha@example.test'), findsOneWidget);
    expect(find.text('Current profile bio'), findsOneWidget);

    await tester.enterText(find.byType(TextField).first, 'Asha Updated');
    await tester.enterText(find.byType(TextField).at(1), 'Updated bio');
    await tester.pump();
    await tester.scrollUntilVisible(
      find.text('Save profile'),
      400,
      scrollable: find.byType(Scrollable).first,
    );
    final saveButton = find.widgetWithText(FilledButton, 'Save profile');
    await tester.ensureVisible(saveButton);
    await tester.pumpAndSettle();
    await tester.tap(saveButton);
    await tester.pumpAndSettle();

    expect(repository.submittedUsers, hasLength(1));
    expect(repository.submittedUsers.single.name, 'Asha Updated');
    expect(find.text('Profile saved'), findsOneWidget);
  });

  testWidgets('profile update failure is surfaced and keeps entered changes',
      (tester) async {
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test'));
    final repository = _ProfileRepository(
      ApiClient(SecureStorageService(const FlutterSecureStorage()), dio: dio),
      failUpdate: true,
    );
    await pumpProfile(
      tester,
      repository: repository,
    );

    await tester.enterText(find.byType(TextField).first, 'Unsaved Name');
    await tester.pump();
    await tester.scrollUntilVisible(
      find.text('Save profile'),
      400,
      scrollable: find.byType(Scrollable).first,
    );
    final saveButton = find.widgetWithText(FilledButton, 'Save profile');
    await tester.ensureVisible(saveButton);
    await tester.pumpAndSettle();
    await tester.tap(saveButton);
    await tester.pumpAndSettle();

    expect(repository.submittedUsers, hasLength(1));
    expect(find.text('Profile saved'), findsNothing);
    expect(
        find.text('The service is temporarily unavailable. Try again shortly.'),
        findsOneWidget);
    expect(repository.submittedUsers.single.name, 'Unsaved Name');
  });

  test('profile repository maps a successful update response', () async {
    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );
   final adapter = DioAdapter(dio: dio);
dio.httpClientAdapter = adapter;

adapter.onPut(
      '/users/1',
      (server) => server.reply(200, {
        'id': 1,
        'name': 'Asha Updated',
        'email': user.email,
        'role': 'STUDENT',
        'status': 'APPROVED',
        'bio': 'Updated bio',
      }),
      data: user.toJson(),
    );
    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    final updated = await repository.updateUser(user);

    expect(updated.id, 1);
    expect(updated.name, 'Asha Updated');
    expect(updated.bio, 'Updated bio');
  });

  test('profile repository propagates update API failures', () async {
    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );
    final adapter = DioAdapter(dio: dio);
    adapter.onPut(
      '/users/1',
      (server) => server.reply(500, {'error': 'Internal server error'}),
      data: user.toJson(),
    );
    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    await expectLater(
      repository.updateUser(user),
      throwsA(
        isA<ApiException>()
            .having((error) => error.kind, 'kind', ApiErrorKind.server)
            .having((error) => error.statusCode, 'statusCode', 500),
      ),
    );
  });
}
