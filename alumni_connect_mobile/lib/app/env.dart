import 'package:flutter/foundation.dart';

abstract final class AppEnvironment {
  /// Set with `--dart-define=API_BASE_URL=https://your-api-host` for production.
  static const _configuredBaseUrl = String.fromEnvironment('API_BASE_URL');

  /// The emulator host is a debug-only default. Profile/release builds must
  /// opt in to their real HTTPS service URL at build time.
  static final String baseUrl = _resolveBaseUrl();

  static String _resolveBaseUrl() {
    final url = _configuredBaseUrl.isNotEmpty
        ? _configuredBaseUrl.trim()
        : (kDebugMode ? 'http://10.0.2.2:8080' : '');
    final uri = Uri.tryParse(url);

    if (kDebugMode) {
      if (uri == null || !uri.hasScheme || uri.host.isEmpty) {
        throw StateError('API_BASE_URL must be a valid absolute URL.');
      }
      return url;
    }

    if (uri == null || uri.scheme != 'https' || uri.host.isEmpty) {
      throw StateError(
        'Profile and release builds require an HTTPS API URL. '
        'Pass --dart-define=API_BASE_URL=https://your-api-host.',
      );
    }
    return url;
  }
}
