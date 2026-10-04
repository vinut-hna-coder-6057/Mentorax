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
  const storageChannel =
      MethodChannel('plugins.it_nomads.com/flutter_secure_storage');
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  const alumni = User(
    id: 1,
    name: 'Asha Alumni',
    email: 'asha@example.test',
    role: UserRole.alumni,
    status: 'APPROVED',
  );

  Future<
      ({
        DioAdapter adapter,
        List<String> requests,
      })> pumpEvents(
    WidgetTester tester, {
    bool failFirstCreate = false,
    Duration createResponseDelay = Duration.zero,
  }) async {
    final requests = <String>[];
    var shouldFailCreate = failFirstCreate;
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
          requests.add('${options.method} ${options.path}');
          if (options.method == 'POST' &&
              options.path == '/events' &&
              shouldFailCreate) {
            shouldFailCreate = false;
            handler.reject(
              DioException(
                requestOptions: options,
                response: Response<dynamic>(
                  requestOptions: options,
                  statusCode: 500,
                  data: {'error': 'Internal server error'},
                ),
              ),
            );
            return;
          }
          handler.next(options);
        },
      ),
    );
    adapter.onGet(
      '/events',
      (server) => server.reply(200, []),
      queryParameters: {'page': 0, 'size': 50},
    );
    adapter.onPost(
      '/events',
      (server) => server.reply(
        200,
        {
          'id': 42,
          'title': 'Mentor meeting',
          'status': 'PENDING',
          'attendeeCount': 0,
        },
        delay: createResponseDelay,
      ),
      data: {
        'title': 'Mentor meeting',
        'description': '',
        'location': '',
        'eventDate': '',
        'category': '',
        'meetingLink': '',
        'imageUrl': null,
      },
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
            notifier.setUser(alumni);
            return notifier;
          }),
        ],
        child: const MaterialApp(home: EventsScreen()),
      ),
    );
    await tester.pumpAndSettle();
    return (adapter: adapter, requests: requests);
  }

  testWidgets('creating an event closes the dialog and remains on events',
      (tester) async {
    final harness = await pumpEvents(
      tester,
    );

    await tester.tap(find.byTooltip('Create event'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Mentor meeting');
    await tester.tap(find.widgetWithText(FilledButton, 'Create'));
    await tester.pumpAndSettle();

    expect(
      harness.requests.where((request) => request == 'POST /events'),
      hasLength(1),
    );
    expect(find.text('Community events'), findsOneWidget);
    expect(find.byType(AlertDialog), findsNothing);
    expect(tester.takeException(), isNull);
  });

  testWidgets('failed creation keeps the editor open and permits a retry',
      (tester) async {
    final harness = await pumpEvents(
      tester,
      failFirstCreate: true,
    );

    await tester.tap(find.byTooltip('Create event'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Mentor meeting');
    await tester.tap(find.widgetWithText(FilledButton, 'Create'));
    await tester.pumpAndSettle();

    expect(find.byType(AlertDialog), findsOneWidget);
    expect(harness.requests.where((request) => request == 'POST /events'),
        hasLength(1));
    expect(tester.takeException(), isNull);

    final retryButton = find.widgetWithText(FilledButton, 'Create');
    expect(tester.widget<FilledButton>(retryButton).onPressed, isNotNull);
    await tester.tap(retryButton);
    await tester.pumpAndSettle();

    expect(harness.requests.where((request) => request == 'POST /events'),
        hasLength(2));
    expect(tester.takeException(), isNull);
  });

  testWidgets('leaving events during creation safely ignores the late result',
      (tester) async {
    final harness = await pumpEvents(
      tester,
      createResponseDelay: const Duration(milliseconds: 250),
    );

    await tester.tap(find.byTooltip('Create event'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Mentor meeting');
    await tester.tap(find.widgetWithText(FilledButton, 'Create'));
    await tester.pump(const Duration(milliseconds: 1));
    expect(harness.requests.where((request) => request == 'POST /events'),
        hasLength(1));

    await tester.pumpWidget(
      const MaterialApp(home: Scaffold(body: Text('Another screen'))),
    );
    await tester.pump(const Duration(milliseconds: 250));
    await tester.pumpAndSettle();

    expect(find.text('Another screen'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('rapid repeated submission issues only one create request',
      (tester) async {
    final harness = await pumpEvents(
      tester,
      createResponseDelay: const Duration(milliseconds: 250),
    );

    await tester.tap(find.byTooltip('Create event'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).first, 'Mentor meeting');
    final createButton = find.widgetWithText(FilledButton, 'Create');
    await tester.tap(createButton);
    await tester.tap(createButton);
    await tester.pump(const Duration(milliseconds: 1));
    expect(
      tester.widget<FilledButton>(find.byType(FilledButton).last).onPressed,
      isNull,
    );
    expect(harness.requests.where((request) => request == 'POST /events'),
        hasLength(1));

    await tester.pumpAndSettle();

    expect(tester.takeException(), isNull);
    expect(harness.requests.where((request) => request == 'POST /events'),
        hasLength(1));
  });
}
