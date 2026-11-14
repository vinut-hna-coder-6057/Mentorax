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
  List<Map<String, dynamic>> contributions = [];
  Map<String, dynamic>? createdContribution;
  Map<String, dynamic>? updatedContribution;
  Map<String, dynamic>? publishedConsent;

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

  @override
  Future<List<Map<String, dynamic>>> alumniKnowledgeContributions() async =>
      contributions;

  @override
  Future<List<Map<String, dynamic>>> knowledgeOpportunities() async => const [];

  @override
  Future<Map<String, dynamic>> createAlumniKnowledgeContribution({
    required String category,
    required String title,
    required String narrative,
    String? companyName,
    String? jobRole,
    int? experienceYear,
    List<Map<String, dynamic>> entries = const [],
  }) async {
    createdContribution = {
      'category': category,
      'title': title,
      'narrative': narrative,
      'companyName': companyName,
      'jobRole': jobRole,
      'experienceYear': experienceYear,
      'entries': entries,
    };
    return {'id': 44};
  }

  @override
  Future<Map<String, dynamic>> updateAlumniKnowledgeContribution(
    int id, {
    required String category,
    required String title,
    required String narrative,
    String? companyName,
    String? jobRole,
    int? experienceYear,
    List<Map<String, dynamic>> entries = const [],
  }) async {
    updatedContribution = {
      'id': id,
      'category': category,
      'title': title,
      'narrative': narrative,
      'companyName': companyName,
      'jobRole': jobRole,
      'experienceYear': experienceYear,
      'entries': entries,
    };
    return {'id': id};
  }

  @override
  Future<Map<String, dynamic>> publishAlumniKnowledge(
    int id, {
    required bool studentVisibilityConsent,
    required bool aiRetrievalConsent,
  }) async {
    publishedConsent = {
      'id': id,
      'studentVisibilityConsent': studentVisibilityConsent,
      'aiRetrievalConsent': aiRetrievalConsent,
    };
    return {'id': id};
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

  testWidgets(
    'structured contribution captures category metadata and entries',
    (tester) async {
      final repository = _KnowledgeRepository();
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appRepositoryProvider.overrideWithValue(repository)],
          child: const MaterialApp(home: AlumniKnowledgeContributionsScreen()),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.text('Create structured draft'));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('contribution-category')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('PROJECT EXPERIENCE').last);
      await tester.pumpAndSettle();
      expect(
        find.textContaining('What problem did the project solve?'),
        findsOneWidget,
      );
      await tester.ensureVisible(find.text('Save private draft'));
      await tester.tap(find.text('Save private draft'));
      await tester.pumpAndSettle();
      expect(find.text('Required'), findsNWidgets(2));

      await tester.enterText(
        find.byKey(const Key('contribution-title')),
        'Queue service',
      );
      await tester.enterText(
        find.byKey(const Key('contribution-company')),
        'Example Co',
      );
      await tester.enterText(
        find.byKey(const Key('contribution-role')),
        'Backend Engineer',
      );
      await tester.enterText(
        find.byKey(const Key('contribution-year')),
        '2024',
      );
      await tester.enterText(
        find.byKey(const Key('contribution-narrative')),
        'Built a queue service and learned from retries.',
      );
      await tester.ensureVisible(find.text('Add answer'));
      await tester.tap(find.text('Add answer'));
      await tester.pumpAndSettle();
      await tester.enterText(
        find.widgetWithText(TextFormField, 'Answer / resource / role detail'),
        'Java',
      );
      await tester.ensureVisible(find.text('Save private draft'));
      await tester.tap(find.text('Save private draft'));
      await tester.pumpAndSettle();

      expect(repository.createdContribution, {
        'category': 'PROJECT_EXPERIENCE',
        'title': 'Queue service',
        'narrative': 'Built a queue service and learned from retries.',
        'companyName': 'Example Co',
        'jobRole': 'Backend Engineer',
        'experienceYear': 2024,
        'entries': [
          {
            'type': 'TECHNOLOGY',
            'position': 0,
            'parentPosition': null,
            'value': 'Java',
            'detail': null,
            'url': null,
            'fromRole': null,
            'toRole': null,
            'year': null,
          },
        ],
      });
    },
  );

  testWidgets(
    'editing preserves structured entries and consent publishes explicitly',
    (tester) async {
      final repository = _KnowledgeRepository()
        ..contributions = [
          {
            'id': 12,
            'version': 1,
            'publishedVersion': null,
            'status': 'DRAFT',
            'category': 'CAREER_JOURNEY',
            'title': 'Role transition',
            'narrative': 'Moved into engineering.',
            'companyName': 'Example Co',
            'jobRole': 'Engineer',
            'experienceYear': 2023,
            'studentVisibilityConsent': false,
            'aiRetrievalConsent': false,
            'entries': [
              {
                'type': 'ROLE_TRANSITION',
                'position': 0,
                'parentPosition': null,
                'value': null,
                'detail': null,
                'url': null,
                'fromRole': 'Analyst',
                'toRole': 'Engineer',
                'year': 2023,
              },
            ],
          },
        ];
      await tester.pumpWidget(
        ProviderScope(
          overrides: [appRepositoryProvider.overrideWithValue(repository)],
          child: const MaterialApp(home: AlumniKnowledgeContributionsScreen()),
        ),
      );
      await tester.pumpAndSettle();
      await tester.tap(find.byType(PopupMenuButton<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Edit as new version'));
      await tester.pumpAndSettle();
      expect(find.text('Analyst'), findsOneWidget);
      await tester.ensureVisible(find.text('Save new draft version'));
      await tester.tap(find.text('Save new draft version'));
      await tester.pumpAndSettle();
      expect(
        repository.updatedContribution?['entries'],
        repository.contributions.first['entries'],
      );

      await tester.tap(find.byType(PopupMenuButton<String>));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Publish with selected consent'));
      await tester.pumpAndSettle();
      expect(find.textContaining('version 1'), findsOneWidget);
      await tester.tap(find.byType(CheckboxListTile).at(0));
      await tester.pumpAndSettle();
      await tester.tap(find.byType(CheckboxListTile).at(1));
      await tester.pumpAndSettle();
      expect(
        tester
            .widget<FilledButton>(find.widgetWithText(FilledButton, 'Publish'))
            .onPressed,
        isNotNull,
      );
      await tester.tap(find.text('Publish'));
      await tester.pumpAndSettle();
      expect(repository.publishedConsent, {
        'id': 12,
        'studentVisibilityConsent': true,
        'aiRetrievalConsent': true,
      });
    },
  );
}
