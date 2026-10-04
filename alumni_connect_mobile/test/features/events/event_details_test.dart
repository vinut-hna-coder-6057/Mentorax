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
  );

  const event = {
    'id': 10,
    'title': 'Mentorax launch',
    'description': 'Community event',
    'eventDate': '2026-11-15T18:00:00Z',
    'location': 'Online',
    'status': 'APPROVED',
    'attendeeCount': 3,
  };
  final requestLog = <String>[];

  Future<void> pumpDetails(
    WidgetTester tester, {
    required bool failRegistration,
  }) async {
    var registered = false;
    var attendeeCount = 3;
    requestLog.clear();
    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );
    final adapter = DioAdapter(dio: dio);
    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) {
          requestLog.add('${options.method} ${options.path}');
          handler.next(options);
        },
      ),
    );
    adapter.onGet(
      '/events',
      (server) => server.replyCallback(
        200,
        (_) => [
          {...event, 'attendeeCount': attendeeCount}
        ],
      ),
      queryParameters: {'page': 0, 'size': 50},
    );
    adapter.onGet(
      '/events/10/registration-status',
      (server) => server.replyCallback(
        200,
        (_) => {'registered': registered},
      ),
    );
    adapter.onPost(
      '/events/register',
      (server) {
        if (failRegistration) {
          server.reply(500, {'error': 'Internal server error'});
          return;
        }
        server.replyCallback(
          200,
          (_) {
            registered = true;
            attendeeCount++;
            return {'message': 'Registered successfully'};
          },
        );
      },
      queryParameters: {'eventId': 10},
    );

    final storage = SecureStorageService(const FlutterSecureStorage());
    final repository = AppRepository(ApiClient(storage, dio: dio));

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
        child: const MaterialApp(home: EventScreen(id: 10)),
      ),
    );
    await tester.pumpAndSettle();
  }

  testWidgets('registration status loads and refreshes after successful RSVP',
      (tester) async {
    await pumpDetails(tester, failRegistration: false);

    expect(find.text('Register'), findsOneWidget);
    await tester.tap(find.text('Register'));
    await tester.pumpAndSettle();

    expect(find.text('Cancel registration'), findsOneWidget);
    expect(find.text('4 attendees'), findsOneWidget);
    expect(
      requestLog
          .where((request) => request == 'GET /events/10/registration-status'),
      hasLength(2),
    );
    expect(
      requestLog.where((request) => request == 'GET /events'),
      hasLength(2),
    );
  });

  testWidgets('failed RSVP shows an error and does not change registered state',
      (tester) async {
    await pumpDetails(tester, failRegistration: true);

    await tester.tap(find.text('Register'));
    await tester.pumpAndSettle();

    expect(find.text('Register'), findsOneWidget);
    expect(find.text('Cancel registration'), findsNothing);
    expect(
      find.text('The service is temporarily unavailable. Try again shortly.'),
      findsOneWidget,
    );
  });
}
