import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class SecureStorageService {
  SecureStorageService(this._storage);
  final FlutterSecureStorage _storage;
  static const _tokenKey = 'access_token';

  /// Prefer a quick path on Android emulators where Keystore-backed reads
  /// can stall the first call for a long time.
  Future<String?> readToken() => _storage.read(
        key: _tokenKey,
        aOptions: const AndroidOptions(
          resetOnError: true,
        ),
      );

  Future<void> writeToken(String token) => _storage.write(
        key: _tokenKey,
        value: token,
        aOptions: const AndroidOptions(
          resetOnError: true,
        ),
      );

  Future<void> clearAuth() => _storage.delete(
        key: _tokenKey,
        aOptions: const AndroidOptions(
          resetOnError: true,
        ),
      );
}
