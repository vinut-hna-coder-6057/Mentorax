import 'dart:async';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/mentor_discovery/mentor_discovery_screen.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter/services.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

class _MentorRepository extends AppRepository {
  _MentorRepository({this.response, this.pending})
      : super(ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: Dio(BaseOptions(baseUrl: 'https://example.test')),
        ));

  Map<String, dynamic>? response;
  Completer<Map<String, dynamic>>? pending;
  final submittedGoals = <String>[];

  @override
  Future<Map<String, dynamic>> discoverMentors(String goal) {
    submittedGoals.add(goal);
    if (pending != null) return pending!.future;
    return Future.value(response!);
  }

  @override
  Future<ConnectionItem> requestConnection(int id) =>
      throw StateError('The test must not send a connection request.');
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const secureStorageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
    secureStorageChannel,
    (call) async => call.method == 'read' ? 'test.jwt.value' : null,
  );

  Future<void> pumpScreen(
      WidgetTester tester, _MentorRepository repository) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: MentorDiscoveryScreen()),
      ),
    );
  }

  const recommendation = {
    'id': 12,
    'name': 'Asha Mentor',
    'jobTitle': 'Data Engineer',
    'company': 'Example Co',
    'skills': ['Python', 'Data Engineering'],
    'relevanceEstimate': 82.5,
    'evidence': ['Skill: Data Engineering', 'Current role: Data Engineer'],
    'uncertainty': 'Availability is unknown.',
    'conversationTopic': 'Ask about their data engineering path.',
    'messageDraft': 'Hi Asha, could I ask about your career path?',
  };

  testWidgets(
      'question input explains discovery and blank question does not submit',
      (tester) async {
    final repository = _MentorRepository(response: {
      'recommendations': <Map<String, dynamic>>[],
      'rankingMethod': 'fallback',
      'fallbackUsed': true,
    });
    await pumpScreen(tester, repository);

    expect(find.text('What would you like to ask a mentor?'), findsOneWidget);
    expect(find.textContaining('not sent to any alumnus'), findsOneWidget);
    expect(find.text('Find Relevant Alumni'), findsNWidgets(2));
    expect(
        find.text('How should I prepare for software engineering internships?'),
        findsOneWidget);
    expect(find.text('How can I improve my DSA and interview skills?'),
        findsOneWidget);
    expect(
        find.text(
            'What skills should I learn to become a machine learning engineer?'),
        findsOneWidget);

    await tester
        .ensureVisible(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.tap(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.pump();

    expect(repository.submittedGoals, isEmpty);
  });

  testWidgets('example question populates the search field without submitting',
      (tester) async {
    tester.view.physicalSize = const Size(320, 800);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _MentorRepository(response: {
      'recommendations': <Map<String, dynamic>>[],
      'rankingMethod': 'fallback',
      'fallbackUsed': true,
    });
    await pumpScreen(tester, repository);

    final example = find.text('How can I improve my DSA and interview skills?');
    await tester.ensureVisible(example);
    await tester.tap(example);

    expect(
        tester.widget<TextField>(find.byType(TextField).first).controller!.text,
        'How can I improve my DSA and interview skills?');
    expect(repository.submittedGoals, isEmpty);
  });

  test('model accepts missing optional fields and long profile content', () {
    final match = MentorMatch.fromJson({
      'id': 17,
      'name': 'Long Mentor Name ' * 20,
      'skills': List<String>.filled(120, 'Professional skill'),
      'evidence': List<String>.filled(120, 'Verified profile evidence'),
      'relevanceEstimate': 25.0,
      'messageDraft': 'Draft text',
      'conversationTopic': 'Conversation topic',
    });

    expect(match.name.length, greaterThan(100));
    expect(match.jobTitle, isNull);
    expect(match.company, isNull);
    expect(match.skills, hasLength(120));
    expect(match.evidence, hasLength(120));
  });

  test('repository posts to the configured API with the secure bearer token',
      () async {
    final dio = Dio(BaseOptions(baseUrl: 'https://api.example.test'));
    final adapter = DioAdapter(dio: dio);
    dio.httpClientAdapter = adapter;
    final requests = <RequestOptions>[];
    dio.interceptors.add(InterceptorsWrapper(onRequest: (options, handler) {
      requests.add(options);
      handler.next(options);
    }));
    adapter.onPost(
      '/ai/mentor-discovery',
      (server) => server.reply(200, {
        'recommendations': <Map<String, dynamic>>[],
        'rankingMethod': 'fallback',
        'fallbackUsed': true,
      }),
      data: {'goal': 'Data engineering'},
    );
    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    final response = await repository.discoverMentors('Data engineering');

    expect(response['fallbackUsed'], isTrue);
    expect(requests, hasLength(1));
    expect(requests.single.uri.toString(),
        'https://api.example.test/ai/mentor-discovery');
    expect(requests.single.data, {'goal': 'Data engineering'});
    expect(requests.single.headers['Authorization'], 'Bearer test.jwt.value');
  });

  testWidgets(
      'submits a career question and renders matched alumni and editable draft',
      (tester) async {
    final repository = _MentorRepository(response: {
      'recommendations': [recommendation],
      'rankingMethod': 'TF-IDF cosine similarity',
      'fallbackUsed': false,
    });
    await pumpScreen(tester, repository);

    await tester.enterText(
        find.byType(TextField).first, 'How can I move into data engineering?');
    await tester
        .ensureVisible(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.tap(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.pumpAndSettle();

    expect(
        repository.submittedGoals, ['How can I move into data engineering?']);
    expect(find.text('Asha Mentor'), findsOneWidget);
    expect(find.text('Data Engineer · Example Co'), findsOneWidget);
    expect(find.textContaining('Profile skills: Python, Data Engineering'),
        findsOneWidget);
    expect(find.textContaining('Skill: Data Engineering'), findsOneWidget);
    expect(find.textContaining('Ask about their data engineering path.'),
        findsOneWidget);
    expect(find.text('Copy draft'), findsOneWidget);
    expect(find.text('Request connection'), findsOneWidget);
    expect(
        find.text('TF-IDF ranking · TF-IDF cosine similarity'), findsOneWidget);
    final draft = find.byType(TextField).last;
    expect(tester.widget<TextField>(draft).controller!.text,
        'Hi Asha, could I ask about your career path?');
    await tester.enterText(draft, 'My reviewed draft');
    expect(
        tester.widget<TextField>(draft).controller!.text, 'My reviewed draft');
  });

  testWidgets(
      'loading state disables repeat submissions and empty results render safely',
      (tester) async {
    final pending = Completer<Map<String, dynamic>>();
    final repository = _MentorRepository(pending: pending);
    await pumpScreen(tester, repository);
    await tester.enterText(find.byType(TextField).first, 'Product design');
    await tester
        .ensureVisible(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.tap(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.pump();

    expect(find.text('Finding relevant alumni…'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull);
    expect(repository.submittedGoals, ['Product design']);

    pending.complete({
      'recommendations': <Map<String, dynamic>>[],
      'rankingMethod': 'Transparent profile keyword overlap (fallback)',
      'fallbackUsed': true,
    });
    await tester.pumpAndSettle();

    expect(find.text('No relevant alumni found'), findsOneWidget);
    expect(repository.submittedGoals, ['Product design']);
  });

  testWidgets('request errors show a retry action that resubmits the same goal',
      (tester) async {
    tester.view.physicalSize = const Size(800, 1000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final repository = _MentorRepository(response: {
      'recommendations': <Map<String, dynamic>>[],
      'rankingMethod': 'fallback',
      'fallbackUsed': true,
    });
    final failing = _RetryRepository(repository);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appRepositoryProvider.overrideWithValue(failing)],
        child: const MaterialApp(home: MentorDiscoveryScreen()),
      ),
    );
    await tester.enterText(find.byType(TextField).first, 'retry goal');
    await tester
        .ensureVisible(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.tap(find.byKey(const ValueKey('findRelevantAlumniButton')));
    await tester.pumpAndSettle();

    expect(find.text('Service unavailable'), findsOneWidget);
    await tester.tap(find.text('Try again'));
    await tester.pumpAndSettle();

    expect(failing.goals, ['retry goal', 'retry goal']);
    expect(find.text('No relevant alumni found'), findsOneWidget);
  });
}

class _RetryRepository extends AppRepository {
  _RetryRepository(this.delegate)
      : super(ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: Dio(BaseOptions(baseUrl: 'https://example.test')),
        ));

  final _MentorRepository delegate;
  final goals = <String>[];
  bool failFirst = true;

  @override
  Future<Map<String, dynamic>> discoverMentors(String goal) async {
    goals.add(goal);
    if (failFirst) {
      failFirst = false;
      throw Exception('Service unavailable');
    }
    return delegate.response!;
  }
}
