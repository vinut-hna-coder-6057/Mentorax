

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
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

  group('Registration API Contract', () {
    test('signup proceeds directly to sign in or existing alumni approval', () {
      expect(
        registrationSuccessLocation(UserRole.student),
        '/login?registered=1',
      );
      expect(
        registrationSuccessLocation(UserRole.alumni),
        '/pending-approval',
      );
    });

    testWidgets('login acknowledges signup without showing verification UI',
        (tester) async {
      await tester.pumpWidget(
        const ProviderScope(
          child: MaterialApp(
            home: LoginScreen(signupComplete: true),
          ),
        ),
      );

      expect(find.text('Your account was created. Sign in to continue.'),
          findsOneWidget);
      expect(find.text('Verify email'), findsNothing);
    });

    // ------------------------------------------------------------
    // TEST 1 — STUDENT REGISTRATION
    // ------------------------------------------------------------
    test('signup sends student credentials and profile to /signup', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      RequestOptions? sentRequest;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            sentRequest = options;
            handler.next(options);
          },
        ),
      );

      const studentUser = User(
        id: 0,
        name: 'Ravi Student',
        email: 'ravi.student@example.com',
        role: UserRole.student,
        status: 'PENDING',
      );

      final expectedPayload = studentUser.toJson(password: 'ValidPassword123');

      adapter.onPost(
        '/signup',
        (server) => server.reply(200, 'Signup successful'),
        data: expectedPayload,
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final response = await repository.signup(
        studentUser,
        'ValidPassword123',
      );

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'POST');
      expect(sentRequest!.path, '/signup');
      expect(sentRequest!.uri.host, 'api.example.test');

      final sentData = sentRequest!.data as Map<String, dynamic>;
      expect(sentData['email'], 'ravi.student@example.com');
      expect(sentData['name'], 'Ravi Student');
      expect(sentData['role'], 'STUDENT');
      expect(sentData['status'], 'PENDING');
      expect(sentData['password'], 'ValidPassword123');

      // Ensure id is not accidentally transmitted in the signup payload
      expect(sentData.containsKey('id'), isFalse);

      expect(response, 'Signup successful');
      expect(adapter.history, hasLength(1));
    });

    // ------------------------------------------------------------
    // TEST 2 — ALUMNI REGISTRATION
    // ------------------------------------------------------------
    test('signup sends alumni credentials and profile to /signup', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);
      RequestOptions? sentRequest;
      dio.interceptors.add(
        InterceptorsWrapper(
          onRequest: (options, handler) {
            sentRequest = options;
            handler.next(options);
          },
        ),
      );

      const alumniUser = User(
        id: 0,
        name: 'Priya Alumni',
        email: 'priya.alumni@example.com',
        role: UserRole.alumni,
        status: 'PENDING',
      );

      final expectedPayload = alumniUser.toJson(password: 'StrongAlumniPass8');

      adapter.onPost(
        '/signup',
        (server) => server.reply(200, 'Signup successful'),
        data: expectedPayload,
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final response = await repository.signup(
        alumniUser,
        'StrongAlumniPass8',
      );

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'POST');
      expect(sentRequest!.path, '/signup');

      final sentData = sentRequest!.data as Map<String, dynamic>;
      expect(sentData['email'], 'priya.alumni@example.com');
      expect(sentData['name'], 'Priya Alumni');
      expect(sentData['role'], 'ALUMNI');
      expect(sentData['status'], 'PENDING');
      expect(sentData['password'], 'StrongAlumniPass8');
      expect(sentData.containsKey('id'), isFalse);

      expect(response, 'Signup successful');
      expect(adapter.history, hasLength(1));
    });

    // ------------------------------------------------------------
    // TEST 3 — INVALID REGISTRATION RESPONSE (CONFLICT / DUPLICATE EMAIL)
    // ------------------------------------------------------------
    test('signup handles 409 Conflict when email already exists', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      const existingUser = User(
        id: 0,
        name: 'Duplicate User',
        email: 'existing@example.com',
        role: UserRole.student,
        status: 'PENDING',
      );

      adapter.onPost(
        '/signup',
        (server) => server.reply(409, 'Email already exists'),
        data: existingUser.toJson(password: 'ValidPassword123'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.signup(existingUser, 'ValidPassword123'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.conflict)
              .having((e) => e.message, 'message',
                  contains('An account already uses this email'))
              .having((e) => e.statusCode, 'statusCode', 409),
        ),
      );
    });

    // ------------------------------------------------------------
    // TEST 4 — INVALID REGISTRATION RESPONSE (VALIDATION FAILURE)
    // ------------------------------------------------------------
    test('signup handles 400 Bad Request on validation failure', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      const invalidUser = User(
        id: 0,
        name: 'Invalid User',
        email: 'invalid@example.com',
        role: UserRole.student,
        status: 'PENDING',
      );

      adapter.onPost(
        '/signup',
        (server) => server.reply(400, 'Password does not meet requirements'),
        data: invalidUser.toJson(password: 'short'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.signup(invalidUser, 'short'),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.validation)
              .having(
                (e) => e.message,
                'message',
                contains('Password does not meet requirements'),
              )
              .having((e) => e.statusCode, 'statusCode', 400),
        ),
      );
    });
  });
}
