import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  test(
    'create, update, and publish send complete structured requests',
    () async {
      final requests = <Map<String, dynamic>>[];
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            if (options.data is Map) {
              requests.add(Map<String, dynamic>.from(options.data as Map));
            }
            handler.next(options);
          },
        ),
      );
      final adapter = DioAdapter(dio: dio);
      final response = {
        'id': 4,
        'version': 2,
        'category': 'PROJECT_EXPERIENCE',
        'title': 'Queue service',
        'narrative': 'Built a queue service.',
        'companyName': 'Example Co',
        'jobRole': 'Backend Engineer',
        'experienceYear': 2024,
        'entries': [
          {
            'type': 'TECHNOLOGY',
            'position': 0,
            'parentPosition': null,
            'value': 'Java',
            'detail': 'Retries',
            'url': null,
            'fromRole': null,
            'toRole': null,
            'year': null,
          },
        ],
      };
      final entry = <String, dynamic>{
        'type': 'TECHNOLOGY',
        'position': 0,
        'value': 'Java',
        'detail': 'Retries',
      };
      final contributionRequest = <String, dynamic>{
        'category': 'PROJECT_EXPERIENCE',
        'title': 'Queue service',
        'narrative': 'Built a queue service.',
        'companyName': 'Example Co',
        'jobRole': 'Backend Engineer',
        'experienceYear': 2024,
        'entries': [entry],
      };
      adapter.onPost(
        '/api/alumni/knowledge',
        (server) => server.reply(201, response),
        data: contributionRequest,
      );
      adapter.onPut(
        '/api/alumni/knowledge/4',
        (server) => server.reply(200, response),
        data: contributionRequest,
      );
      adapter.onPost(
        '/api/alumni/knowledge/4/publish',
        (server) => server.reply(200, response),
        data: {'studentVisibilityConsent': true, 'aiRetrievalConsent': false},
      );
      final repository = AppRepository(
        ApiClient(SecureStorageService(const FlutterSecureStorage()), dio: dio),
      );
      final created = await repository.createAlumniKnowledgeContribution(
        category: 'PROJECT_EXPERIENCE',
        title: 'Queue service',
        narrative: 'Built a queue service.',
        companyName: 'Example Co',
        jobRole: 'Backend Engineer',
        experienceYear: 2024,
        entries: [entry],
      );
      final updated = await repository.updateAlumniKnowledgeContribution(
        4,
        category: 'PROJECT_EXPERIENCE',
        title: 'Queue service',
        narrative: 'Built a queue service.',
        companyName: 'Example Co',
        jobRole: 'Backend Engineer',
        experienceYear: 2024,
        entries: [entry],
      );
      final published = await repository.publishAlumniKnowledge(
        4,
        studentVisibilityConsent: true,
        aiRetrievalConsent: false,
      );

      expect(created['entries'], response['entries']);
      expect(updated['entries'], response['entries']);
      expect(published['version'], 2);
      expect(requests[0], contributionRequest);
      expect(requests[1], requests[0]);
      expect(requests[2], {
        'studentVisibilityConsent': true,
        'aiRetrievalConsent': false,
      });
    },
  );
}
