abstract final class AppEnvironment {
  /// Override with `--dart-define=API_BASE_URL=https://api.example.com`.
  static const baseUrl = String.fromEnvironment(
    'API_BASE_URL',
    defaultValue: 'http://10.0.2.2:8080',
  );
}
