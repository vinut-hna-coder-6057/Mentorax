import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http_mock_adapter/http_mock_adapter.dart';

import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const storageChannel = MethodChannel(
    'plugins.it_nomads.com/flutter_secure_storage',
  );
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(storageChannel, (call) async => null);

  const sampleApprovedEvent = {
    'id': 10,
    'title': 'Alumni Homecoming 2026',
    'description': 'Annual alumni gathering and networking session',
    'location': 'Main Campus Quad',
    'eventDate': '2026-11-15T18:00:00Z',
    'status': 'APPROVED',
    'category': 'Networking',
    'meetingLink': 'https://meet.google.com/abc-defg-hij',
    'createdBy': 'alumni.lead@example.com',
    'attendeeCount': 42,
    'imageUrl': 'https://example.com/images/homecoming.png',
  };

  const samplePendingEvent = {
    'id': 11,
    'title': 'Flutter Architecture Workshop',
    'description': 'Deep dive into clean architecture in Flutter',
    'location': 'Virtual',
    'eventDate': '2026-10-20T14:00:00Z',
    'status': 'PENDING',
    'category': 'Workshop',
    'meetingLink': 'https://meet.google.com/xyz-uvwx-rst',
    'createdBy': 'dev.student@example.com',
    'attendeeCount': 5,
    'imageUrl': null,
  };

  const sampleAttendeesList = [
    {
      'id': 101,
      'studentEmail': 'student1@example.com',
      'registeredAt': '2026-09-20T10:30:00Z',
      'user': {
        'id': 1,
        'name': 'Alice Student',
        'email': 'student1@example.com',
      },
    },
    {
      'id': 102,
      'registeredAt': '2026-09-21T12:00:00Z',
      'user': {
        'id': 2,
        'name': 'Bob Alum',
        'email': 'bob@example.com',
      },
    },
  ];

  group('Events API Contract', () {
    // ------------------------------------------------------------
    // TEST 1 — FETCH EVENTS (DEFAULT / APPROVED)
    // ------------------------------------------------------------
    test('events() fetches list from GET /events and maps EventItem models',
        () async {
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

      adapter.onGet(
        '/events',
        (server) => server.reply(
          200,
          [
            sampleApprovedEvent,
            samplePendingEvent,
          ],
        ),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final events = await repository.events();

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/events');
      expect(
        sentRequest!.queryParameters,
        {'page': 0, 'size': 50},
      );
      expect(sentRequest!.uri.host, 'api.example.test');
      expect(adapter.history, hasLength(1));

      expect(events, hasLength(2));

      final first = events[0];
      expect(first.id, 10);
      expect(first.title, 'Alumni Homecoming 2026');
      expect(
          first.description, 'Annual alumni gathering and networking session');
      expect(first.location, 'Main Campus Quad');
      expect(first.eventDate, '2026-11-15T18:00:00Z');
      expect(first.status, 'APPROVED');
      expect(first.category, 'Networking');
      expect(first.meetingLink, 'https://meet.google.com/abc-defg-hij');
      expect(first.createdBy, 'alumni.lead@example.com');
      expect(first.attendeeCount, 42);
      expect(first.imageUrl, 'https://example.com/images/homecoming.png');

      final second = events[1];
      expect(second.id, 11);
      expect(second.title, 'Flutter Architecture Workshop');
      expect(second.status, 'PENDING');
      expect(second.category, 'Workshop');
      expect(second.attendeeCount, 5);
      expect(second.imageUrl, isNull);
    });

    // ------------------------------------------------------------
    // TEST 2 — FETCH ALL EVENTS (ADMIN / ALL)
    // ------------------------------------------------------------
    test(
        'events(all: true) fetches list from GET /events/all and maps EventItem models',
        () async {
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

      adapter.onGet(
        '/events/all',
        (server) => server.reply(
          200,
          [
            sampleApprovedEvent,
            samplePendingEvent,
          ],
        ),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final events = await repository.events(all: true);

      expect(
        sentRequest!.queryParameters,
        {'page': 0, 'size': 50},
      );
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/events/all');
      expect(
        sentRequest!.queryParameters,
        {'page': 0, 'size': 50},
      );
      expect(adapter.history, hasLength(1));

      expect(events, hasLength(2));
      expect(events[0].id, 10);
      expect(events[1].id, 11);
    });

    // ------------------------------------------------------------
    // TEST 3 — REGISTER FOR EVENT
    // ------------------------------------------------------------
    test(
        'registerEvent sends POST to /events/register with eventId query parameter',
        () async {
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

      adapter.onPost(
        '/events/register',
        (server) => server.reply(200, ''),
        queryParameters: {'eventId': 10},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await repository.registerEvent(10);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'POST');
      expect(sentRequest!.path, '/events/register');
      expect(sentRequest!.queryParameters, {'eventId': 10});
      expect(sentRequest!.data, isNull);
      expect(adapter.history, hasLength(1));
    });

    // ------------------------------------------------------------
    // TEST 4 — CANCEL EVENT REGISTRATION
    // ------------------------------------------------------------
    test(
        'cancelRegistration sends DELETE to /events/register with eventId query parameter',
        () async {
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

      adapter.onDelete(
        '/events/register',
        (server) => server.reply(200, ''),
        queryParameters: {'eventId': 10},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await repository.cancelRegistration(10);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'DELETE');
      expect(sentRequest!.path, '/events/register');
      expect(sentRequest!.queryParameters, {'eventId': 10});
      expect(sentRequest!.data, isNull);
      expect(adapter.history, hasLength(1));
    });

    test('eventRegistrationStatus fetches the authenticated registration state',
        () async {
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
      adapter.onGet(
        '/events/10/registration-status',
        (server) => server.reply(200, {'registered': true}),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      expect(await repository.eventRegistrationStatus(10), isTrue);
      expect(sentRequest?.method, 'GET');
      expect(sentRequest?.path, '/events/10/registration-status');
      expect(adapter.history, hasLength(1));
    });

    // ------------------------------------------------------------
    // TEST 5 — CREATE EVENT
    // ------------------------------------------------------------
    test(
        'createEvent sends POST to /events with JSON body and returns created EventItem',
        () async {
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

      const eventInput = EventItem(
        id: 0,
        title: 'Tech Talk: Microservices',
        description: 'Scalable backend architectures',
        location: 'Hall B',
        eventDate: '2026-10-15T10:00:00Z',
        category: 'Workshop',
        meetingLink: 'https://meet.example.com/tech-talk',
        imageUrl: 'https://example.com/tech.png',
      );

      final createdEventResponse = {
        'id': 42,
        'title': 'Tech Talk: Microservices',
        'description': 'Scalable backend architectures',
        'location': 'Hall B',
        'eventDate': '2026-10-15T10:00:00Z',
        'status': 'PENDING',
        'category': 'Workshop',
        'meetingLink': 'https://meet.example.com/tech-talk',
        'createdBy': 'creator@example.com',
        'attendeeCount': 0,
        'imageUrl': 'https://example.com/tech.png',
      };

      adapter.onPost(
        '/events',
        (server) => server.reply(200, createdEventResponse),
        data: eventInput.toJson(),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final created = await repository.createEvent(eventInput);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'POST');
      expect(sentRequest!.path, '/events');
      expect(sentRequest!.data, eventInput.toJson());
      expect(adapter.history, hasLength(1));

      expect(created.id, 42);
      expect(created.title, 'Tech Talk: Microservices');
      expect(created.description, 'Scalable backend architectures');
      expect(created.location, 'Hall B');
      expect(created.eventDate, '2026-10-15T10:00:00Z');
      expect(created.status, 'PENDING');
      expect(created.category, 'Workshop');
      expect(created.meetingLink, 'https://meet.example.com/tech-talk');
      expect(created.createdBy, 'creator@example.com');
      expect(created.attendeeCount, 0);
      expect(created.imageUrl, 'https://example.com/tech.png');
    });

    // ------------------------------------------------------------
    // TEST 6 — UPDATE EVENT
    // ------------------------------------------------------------
    test(
        'updateEvent sends PUT to /events/:id with JSON body and returns updated EventItem',
        () async {
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

      const eventUpdate = EventItem(
        id: 42,
        title: 'Tech Talk: Microservices (Updated)',
        description: 'Updated architecture topic',
        location: 'Auditorium C',
        eventDate: '2026-10-16T11:00:00Z',
        category: 'Seminar',
        meetingLink: 'https://meet.example.com/tech-talk-updated',
        imageUrl: 'https://example.com/tech-updated.png',
      );

      final updatedEventResponse = {
        'id': 42,
        'title': 'Tech Talk: Microservices (Updated)',
        'description': 'Updated architecture topic',
        'location': 'Auditorium C',
        'eventDate': '2026-10-16T11:00:00Z',
        'status': 'APPROVED',
        'category': 'Seminar',
        'meetingLink': 'https://meet.example.com/tech-talk-updated',
        'createdBy': 'creator@example.com',
        'attendeeCount': 10,
        'imageUrl': 'https://example.com/tech-updated.png',
      };

      adapter.onPut(
        '/events/42',
        (server) => server.reply(200, updatedEventResponse),
        data: eventUpdate.toJson(),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final updated = await repository.updateEvent(eventUpdate);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'PUT');
      expect(sentRequest!.path, '/events/42');
      expect(sentRequest!.data, eventUpdate.toJson());
      expect(adapter.history, hasLength(1));

      expect(updated.id, 42);
      expect(updated.title, 'Tech Talk: Microservices (Updated)');
      expect(updated.description, 'Updated architecture topic');
      expect(updated.location, 'Auditorium C');
      expect(updated.eventDate, '2026-10-16T11:00:00Z');
      expect(updated.status, 'APPROVED');
      expect(updated.category, 'Seminar');
      expect(updated.attendeeCount, 10);
    });

    // ------------------------------------------------------------
    // TEST 7 — DELETE EVENT
    // ------------------------------------------------------------
    test('deleteEvent sends DELETE to /events/:id and succeeds', () async {
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

      adapter.onDelete(
        '/events/42',
        (server) => server.reply(200, ''),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await repository.deleteEvent(42);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'DELETE');
      expect(sentRequest!.path, '/events/42');
      expect(sentRequest!.data, isNull);
      expect(adapter.history, hasLength(1));
    });

    // ------------------------------------------------------------
    // TEST 8 — FETCH EVENT ATTENDEES
    // ------------------------------------------------------------
    test(
        'attendees sends GET to /events/attendees/:id and maps EventAttendee models',
        () async {
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

      adapter.onGet(
        '/events/attendees/10',
        (server) => server.reply(200, sampleAttendeesList),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      final attendees = await repository.attendees(10);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'GET');
      expect(sentRequest!.path, '/events/attendees/10');
      expect(adapter.history, hasLength(1));

      expect(attendees, hasLength(2));

      expect(attendees[0].id, 101);
      expect(attendees[0].email, 'student1@example.com');
      expect(attendees[0].name, 'Alice Student');
      expect(attendees[0].registeredAt, '2026-09-20T10:30:00Z');

      expect(attendees[1].id, 102);
      expect(attendees[1].email, 'bob@example.com');
      expect(attendees[1].name, 'Bob Alum');
      expect(attendees[1].registeredAt, '2026-09-21T12:00:00Z');
    });

    // ------------------------------------------------------------
    // TEST 9 — APPROVE EVENT (ADMIN)
    // ------------------------------------------------------------
    test('approveEvent sends PUT to /events/approve/:id and succeeds',
        () async {
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

      adapter.onPut(
        '/events/approve/11',
        (server) => server.reply(200, ''),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await repository.approveEvent(11);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'PUT');
      expect(sentRequest!.path, '/events/approve/11');
      expect(sentRequest!.data, isNull);
      expect(adapter.history, hasLength(1));
    });

    // ------------------------------------------------------------
    // TEST 10 — REJECT EVENT (ADMIN)
    // ------------------------------------------------------------
    test('rejectEvent sends PUT to /events/reject/:id and succeeds', () async {
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

      adapter.onPut(
        '/events/reject/11',
        (server) => server.reply(200, ''),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await repository.rejectEvent(11);

      expect(sentRequest, isNotNull);
      expect(sentRequest!.method, 'PUT');
      expect(sentRequest!.path, '/events/reject/11');
      expect(sentRequest!.data, isNull);
      expect(adapter.history, hasLength(1));
    });
  });

  group('Events API Contract Errors', () {
    // ------------------------------------------------------------
    // ERROR 1 — 401 UNAUTHORIZED ON PROTECTED EVENTS ENDPOINT
    // ------------------------------------------------------------
    test('events handles 401 Unauthorized when session is expired', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onGet(
        '/events',
        (server) => server.reply(401, 'Unauthorized'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.events(),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.unauthorized)
              .having((e) => e.statusCode, 'statusCode', 401)
              .having((e) => e.message, 'message',
                  contains('Your session has expired')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 2 — 400 BAD REQUEST ON CREATE EVENT VALIDATION FAILURE
    // ------------------------------------------------------------
    test('createEvent handles 400 Bad Request on validation error', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      const invalidEvent = EventItem(
        id: 0,
        title: '',
      );

      adapter.onPost(
        '/events',
        (server) => server.reply(400, 'Title cannot be empty'),
        data: invalidEvent.toJson(),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.createEvent(invalidEvent),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.validation)
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.message, 'message',
                  contains('Title cannot be empty')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 3 — 403 FORBIDDEN ON ADMIN APPROVE EVENT
    // ------------------------------------------------------------
    test(
        'approveEvent handles 403 Forbidden when user is not authorized as admin',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPut(
        '/events/approve/10',
        (server) => server.reply(403, 'Forbidden'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.approveEvent(10),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.forbidden)
              .having((e) => e.statusCode, 'statusCode', 403)
              .having((e) => e.message, 'message', contains('permission')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 4 — 404 NOT FOUND ON REGISTER EVENT
    // ------------------------------------------------------------
    test('registerEvent handles 404 Not Found when event does not exist',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPost(
        '/events/register',
        (server) => server.reply(404, 'Event not found'),
        queryParameters: {'eventId': 999},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.registerEvent(999),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.notFound)
              .having((e) => e.statusCode, 'statusCode', 404)
              .having((e) => e.message, 'message', contains('not found')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 5 — 409 CONFLICT ON REGISTER EVENT
    // ------------------------------------------------------------
    test('registerEvent handles 409 Conflict when user already registered',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onPost(
        '/events/register',
        (server) => server.reply(409, 'Already registered for this event'),
        queryParameters: {'eventId': 10},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.registerEvent(10),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.conflict)
              .having((e) => e.statusCode, 'statusCode', 409)
              .having((e) => e.message, 'message',
                  contains('Already registered for this event')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 6 — 400 BAD REQUEST ON CANCEL REGISTRATION
    // ------------------------------------------------------------
    test(
        'cancelRegistration handles 400 Bad Request when user was not registered',
        () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onDelete(
        '/events/register',
        (server) => server.reply(400, 'Registration not found for user'),
        queryParameters: {'eventId': 10},
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.cancelRegistration(10),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.validation)
              .having((e) => e.statusCode, 'statusCode', 400)
              .having((e) => e.message, 'message',
                  contains('Registration not found')),
        ),
      );
    });

    // ------------------------------------------------------------
    // ERROR 7 — 500 SERVER ERROR ON DELETE EVENT
    // ------------------------------------------------------------
    test('deleteEvent handles 500 Server Error', () async {
      final dio = Dio(
        BaseOptions(
          baseUrl: 'https://api.example.test',
          responseType: ResponseType.plain,
        ),
      );
      final adapter = DioAdapter(dio: dio);

      adapter.onDelete(
        '/events/42',
        (server) => server.reply(500, 'Internal Server Error'),
      );

      final repository = AppRepository(
        ApiClient(
          SecureStorageService(const FlutterSecureStorage()),
          dio: dio,
        ),
      );

      await expectLater(
        repository.deleteEvent(42),
        throwsA(
          isA<ApiException>()
              .having((e) => e.kind, 'kind', ApiErrorKind.server)
              .having((e) => e.statusCode, 'statusCode', 500)
              .having((e) => e.message, 'message',
                  contains('temporarily unavailable')),
        ),
      );
    });
  });
  // ------------------------------------------------------------
  // ERROR 8 — 429 TOO MANY REQUESTS
  // ------------------------------------------------------------
  test('events handles 429 Too Many Requests', () async {
    final dio = Dio(
      BaseOptions(
        baseUrl: 'https://api.example.test',
        responseType: ResponseType.plain,
      ),
    );

    final adapter = DioAdapter(dio: dio);

    adapter.onGet(
      '/events',
      (server) => server.reply(
        429,
        'Too many requests',
      ),
    );

    final repository = AppRepository(
      ApiClient(
        SecureStorageService(const FlutterSecureStorage()),
        dio: dio,
      ),
    );

    await expectLater(
      repository.events(),
      throwsA(
        isA<ApiException>()
            .having(
              (e) => e.kind,
              'kind',
              ApiErrorKind.rateLimited,
            )
            .having(
              (e) => e.statusCode,
              'statusCode',
              429,
            )
            .having(
              (e) => e.message,
              'message',
              contains('Too many requests'),
            ),
      ),
    );
  });
}
