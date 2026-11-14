import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/knowledge/knowledge_companion_screens.dart';

class _KnowledgeRepository extends AppRepository {
  _KnowledgeRepository()
    : super(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: Dio(BaseOptions(baseUrl: 'https://api.example.test')),
        ),
      );

  var asks = 0;
  var roadmapCreates = 0;

  @override
  Future<List<Map<String, dynamic>>> knowledgeConversations({
    int page = 0,
  }) async => const [];

  @override
  Future<Map<String, dynamic>> createKnowledgeConversation({
    String? title,
  }) async => const {'id': 7, 'title': 'Knowledge question'};

  @override
  Future<List<Map<String, dynamic>>> knowledgeMessages(
    int conversationId, {
    int page = 0,
  }) async => const [];

  @override
  Future<List<Map<String, dynamic>>> askKnowledgeQuestion(
    int conversationId,
    String content,
  ) async {
    asks++;
    return [
      {'id': 1, 'speaker': 'USER', 'content': content, 'sources': []},
      {
        'id': 2,
        'speaker': 'ASSISTANT',
        'content': 'Alumni-reported example.',
        'answerMode': 'DETERMINISTIC_GROUNDED',
        'sources': [
          {
            'contributionId': 42,
            'version': 3,
            'title': 'Backend project',
            'currentlyEligible': true,
          },
        ],
        'mentors': [],
      },
    ];
  }

  @override
  Future<List<Map<String, dynamic>>> knowledgeRoadmaps() async => const [];

  @override
  Future<Map<String, dynamic>> createKnowledgeRoadmap(String goal) async {
    roadmapCreates++;
    return {
      'id': 9,
      'title': goal,
      'goal': goal,
      'evidenceStatus': 'GENERAL_ONLY',
      'items': [],
    };
  }
}

void main() {
  testWidgets('student can ask a question and see grounded source references', (
    tester,
  ) async {
    final repository = _KnowledgeRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: KnowledgeCompanionScreen()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('knowledge-question')),
      'How should I prepare for a backend project?',
    );
    await tester.tap(find.byTooltip('Send question'));
    await tester.pumpAndSettle();

    expect(repository.asks, 1);
    expect(find.text('Alumni-reported example.'), findsOneWidget);
    expect(find.textContaining('Contribution 42 · version 3'), findsOneWidget);
    expect(
      find.textContaining('deterministic, source-grounded'),
      findsOneWidget,
    );
  });

  testWidgets('roadmap creation sends the student goal to the backend', (
    tester,
  ) async {
    final repository = _KnowledgeRepository();
    await tester.pumpWidget(
      ProviderScope(
        overrides: [appRepositoryProvider.overrideWithValue(repository)],
        child: const MaterialApp(home: KnowledgeRoadmapsScreen()),
      ),
    );
    await tester.pumpAndSettle();

    await tester.enterText(
      find.byKey(const Key('roadmap-goal')),
      'Prepare for a backend internship',
    );
    await tester.tap(find.text('Create roadmap'));
    await tester.pumpAndSettle();

    expect(repository.roadmapCreates, 1);
    expect(find.text('Prepare for a backend internship'), findsNothing);
  });
}
