import 'package:dio/dio.dart';

import 'api_exception.dart';

ApiException toApiException(Object error) {
  if (error is ApiException) {
    return error;
  }
  if (error is DioException) {
    final status = error.response?.statusCode;
    final message = _message(error.response?.data);
    if (status == 401) {
      return ApiException(ApiErrorKind.unauthorized,
          'Your session has expired. Please sign in again.',
          statusCode: status);
    }
    if (status == 403) {
      return ApiException(
          ApiErrorKind.forbidden, 'You do not have permission to do that.',
          statusCode: status);
    }
    if (status == 404) {
      return ApiException(
          ApiErrorKind.notFound, 'The requested item was not found.',
          statusCode: status);
    }
   if (status == 409) {
  return ApiException(
    ApiErrorKind.conflict,
    message,
    statusCode: status,
  );
}

if (status == 429) {
  final retryAfter = error.response?.headers.value('retry-after');

  final rateLimitMessage =
      retryAfter != null && retryAfter.trim().isNotEmpty
          ? 'Too many requests. Please wait $retryAfter seconds and try again.'
          : 'Too many requests. Please wait a moment and try again.';

  return ApiException(
    ApiErrorKind.rateLimited,
    rateLimitMessage,
    statusCode: status,
  );
}

if (status == 400 || status == 422) {
  return ApiException(
    ApiErrorKind.validation,
    message,
    statusCode: status,
  );
}
    if (status != null && status >= 500) {
      return ApiException(ApiErrorKind.server,
          'The service is temporarily unavailable. Try again shortly.',
          statusCode: status);
    }
    if (error.type == DioExceptionType.connectionTimeout ||
        error.type == DioExceptionType.receiveTimeout ||
        error.type == DioExceptionType.sendTimeout) {
      return const ApiException(ApiErrorKind.timeout,
          'The request timed out. Check your connection and retry.');
    }
    return const ApiException(ApiErrorKind.network,
        'Unable to reach Alumni Connect. Check your connection and retry.');
  }
  return const ApiException(
      ApiErrorKind.unknown, 'Something went wrong. Please try again.');
}
String _message(dynamic data) {
  if (data is String && data.isNotEmpty) {
    return data;
  }

  if (data is Map && data['error'] is String) {
    return data['error'] as String;
  }

  if (data is Map && data['message'] is String) {
    return data['message'] as String;
  }

  return 'The request could not be completed.';
}