enum ApiErrorKind {
  network,
  timeout,
  unauthorized,
  pendingApproval,
  forbidden,
  notFound,
  conflict,
  validation,
  rateLimited,
  server,
  malformed,
  unknown,
}

class ApiException implements Exception {
  const ApiException(this.kind, this.message, {this.statusCode});
  final ApiErrorKind kind;
  final String message;
  final int? statusCode;

  @override
  String toString() => message;
}
