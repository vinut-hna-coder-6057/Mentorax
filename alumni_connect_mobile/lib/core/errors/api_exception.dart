enum ApiErrorKind {
  network,
  timeout,
  unauthorized,
  forbidden,
  notFound,
  conflict,
  validation,
  server,
  malformed,
  unknown
}

class ApiException implements Exception {
  const ApiException(this.kind, this.message, {this.statusCode});
  final ApiErrorKind kind;
  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}
