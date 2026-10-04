import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:alumni_connect_mobile/core/errors/api_exception.dart';
import 'package:alumni_connect_mobile/core/errors/error_handler.dart';

void main() {
  test('decodes JSON error strings for validation responses', () {
    final request = RequestOptions(path: '/signup');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.badResponse,
      response: Response<dynamic>(
        requestOptions: request,
        statusCode: 400,
        data: '{"error":"Request validation failed"}',
      ),
    );

    final mapped = toApiException(error, requestPath: '/signup');

    expect(mapped.kind, ApiErrorKind.validation);
    expect(mapped.message, 'Request validation failed');
    expect(mapped.statusCode, 400);
  });

  test('maps login credentials failure without describing an expired session',
      () {
    final request = RequestOptions(path: '/login');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.badResponse,
      response: Response<dynamic>(
        requestOptions: request,
        statusCode: 401,
        data: '{"error":"Invalid email, password, or account type"}',
      ),
    );

    final mapped = toApiException(error, requestPath: '/login');

    expect(mapped.kind, ApiErrorKind.unauthorized);
    expect(mapped.message, contains('selected account type'));
  });

  test('identifies a valid login blocked by pending alumni approval', () {
    final request = RequestOptions(path: '/login');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.badResponse,
      response: Response<dynamic>(
        requestOptions: request,
        statusCode: 403,
        data: 'Account pending approval',
      ),
    );

    final mapped = toApiException(error, requestPath: '/login');

    expect(mapped.kind, ApiErrorKind.pendingApproval);
    expect(mapped.statusCode, 403);
    expect(mapped.message, contains('awaiting administrator approval'));
  });

  test('duplicate registration recommends signing into the existing account',
      () {
    final request = RequestOptions(path: '/signup');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.badResponse,
      response: Response<dynamic>(
        requestOptions: request,
        statusCode: 409,
        data: '{"error":"Email already exists."}',
      ),
    );

    final mapped = toApiException(error, requestPath: '/signup');

    expect(mapped.kind, ApiErrorKind.conflict);
    expect(mapped.message, contains('Sign in with the existing account'));
    expect(mapped.message, isNot(contains('verification')));
  });

  test('keeps protected-route 401 distinct from login failure', () {
    final request = RequestOptions(path: '/users');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.badResponse,
      response: Response<dynamic>(
        requestOptions: request,
        statusCode: 401,
        data: '{"error":"Invalid credentials"}',
      ),
    );

    expect(
      toApiException(error, requestPath: '/users').message,
      contains('session has expired'),
    );
  });

  test('maps request timeout without automatically retrying', () {
    final request = RequestOptions(path: '/signup');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.connectionTimeout,
    );

    final mapped = toApiException(error, requestPath: '/signup');

    expect(mapped.kind, ApiErrorKind.timeout);
    expect(mapped.message, contains('timed out'));
  });

  test('maps connection acceptance server errors to a safe retry message', () {
    final request = RequestOptions(path: '/connections/101');
    final error = DioException(
      requestOptions: request,
      type: DioExceptionType.badResponse,
      response: Response<dynamic>(
        requestOptions: request,
        statusCode: 500,
        data: '{"error":"Internal server error"}',
      ),
    );

    final mapped = toApiException(error, requestPath: '/connections/101');

    expect(mapped.kind, ApiErrorKind.server);
    expect(mapped.statusCode, 500);
    expect(mapped.message, contains('Try again shortly'));
  });
}
