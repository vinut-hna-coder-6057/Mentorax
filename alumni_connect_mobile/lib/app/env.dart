import 'package:flutter/foundation.dart';
import 'api_endpoints.dart';

abstract final class AppEnvironment {
  /// The release default is the Spring Boot service; Python is configured only on Spring.
  static const productionBaseUrl = ApiEndpoints.springProductionBaseUrl;

  /// Set with `--dart-define=API_BASE_URL=...` for local, staging, or CI overrides.
  static const _configuredBaseUrl = String.fromEnvironment('API_BASE_URL');

  /// The emulator host is a debug-only default. Profile/release builds must
  /// opt in to their real HTTPS service URL at build time.
  static final String baseUrl = resolveBaseUrl(
    debug: kDebugMode,
    configuredBaseUrl: _configuredBaseUrl,
  );

  /// Validates a compile-time override and applies the build-mode default.
  static String resolveBaseUrl({
    required bool debug,
    String configuredBaseUrl = '',
  }) => ApiEndpoints.resolveSpringBaseUrl(
    debug: debug,
    configuredBaseUrl: configuredBaseUrl,
  );
}
