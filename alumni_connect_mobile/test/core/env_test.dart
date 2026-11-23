import 'package:flutter_test/flutter_test.dart';
import 'package:alumni_connect_mobile/app/env.dart';

void main() {
  group('AppEnvironment', () {
    test('uses local Android emulator URL only for unconfigured debug builds', () {
      expect(
        AppEnvironment.resolveBaseUrl(debug: true),
        'http://10.0.2.2:8080',
      );
    });

    test('uses the centrally configured Render Spring API for release builds', () {
      expect(
        AppEnvironment.resolveBaseUrl(debug: false),
        'https://mentorax-backend.onrender.com',
      );
      expect(AppEnvironment.productionBaseUrl, contains('mentorax-backend.onrender.com'));
    });

    test('allows test, local, and staging overrides without production access', () {
      expect(
        AppEnvironment.resolveBaseUrl(
          debug: false,
          configuredBaseUrl: 'https://api.example.test',
        ),
        'https://api.example.test',
      );
    });

    test('rejects the retired Railway host and insecure release URLs', () {
      expect(
        () => AppEnvironment.resolveBaseUrl(
          debug: true,
          configuredBaseUrl: 'https://mentorax-production.up.railway.app',
        ),
        throwsStateError,
      );
      expect(
        () => AppEnvironment.resolveBaseUrl(
          debug: false,
          configuredBaseUrl: 'http://mentorax-backend.onrender.com',
        ),
        throwsStateError,
      );
    });
  });
}
