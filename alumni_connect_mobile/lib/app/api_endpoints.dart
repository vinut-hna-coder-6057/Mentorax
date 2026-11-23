/// Shared API endpoint policy used by the Flutter app and CI validation tool.
abstract final class ApiEndpoints {
  static const springProductionBaseUrl =
      'https://mentorax-backend.onrender.com';

  static String resolveSpringBaseUrl({
    required bool debug,
    String configuredBaseUrl = '',
  }) {
    final url = configuredBaseUrl.trim().isNotEmpty
        ? configuredBaseUrl.trim()
        : (debug ? 'http://10.0.2.2:8080' : springProductionBaseUrl);
    final uri = Uri.tryParse(url);
    if (uri?.host.toLowerCase() == 'mentorax-production.up.railway.app') {
      throw StateError('The configured API URL points to a retired backend.');
    }

    if (debug) {
      if (uri == null || !uri.hasScheme || uri.host.isEmpty) {
        throw StateError('API_BASE_URL must be a valid absolute URL.');
      }
      return url;
    }

    if (uri == null || uri.scheme != 'https' || uri.host.isEmpty) {
      throw StateError(
        'Profile and release builds require an HTTPS API URL. '
        'Pass --dart-define=API_BASE_URL=https://your-api-host or use the production default.',
      );
    }
    return url;
  }
}
