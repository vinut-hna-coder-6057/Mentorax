import 'dart:convert';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../core/errors/error_handler.dart';
import '../core/network/api_client.dart';
import '../core/network/realtime_service.dart';
import '../core/storage/secure_storage_service.dart';
import '../features/app_repository.dart';
import '../shared/models/models.dart';

final secureStorageProvider =
    Provider((_) => SecureStorageService(const FlutterSecureStorage()));
final sessionExpiredProvider = StateProvider<bool>((ref) => false);
final themeModeProvider = StateNotifierProvider<ThemeModeNotifier, ThemeMode>(
    (ref) => ThemeModeNotifier());

class ThemeModeNotifier extends StateNotifier<ThemeMode> {
  ThemeModeNotifier() : super(ThemeMode.system) {
    ready = _restore();
  }

  late final Future<void> ready;
  bool _wasChanged = false;

  Future<void> _restore() async {
    try {
      final preferences = await SharedPreferences.getInstance();
      if (_wasChanged) return;
      final saved = preferences.getString('theme_mode');
      state = ThemeMode.values.firstWhere(
        (mode) => mode.name == saved,
        orElse: () => ThemeMode.system,
      );
    } catch (error, stackTrace) {
      debugPrint('Unable to restore the theme preference: $error\n$stackTrace');
    }
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    _wasChanged = true;
    state = mode;
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString('theme_mode', mode.name);
  }
}

final apiClientProvider = Provider((ref) {
  return ApiClient(
    ref.watch(secureStorageProvider),
    onUnauthorized: () {
      ref.read(sessionExpiredProvider.notifier).state = true;
    },
  );
});
final appRepositoryProvider =
    Provider((ref) => AppRepository(ref.watch(apiClientProvider)));
final realtimeServiceProvider = Provider<RealtimeService>((ref) {
  final service = RealtimeService();
  ref.onDispose(service.dispose);
  return service;
});
final authProvider =
    StateNotifierProvider<AuthNotifier, AsyncValue<User?>>((ref) {
  final notifier = AuthNotifier(
    ref.watch(appRepositoryProvider),
    ref.watch(secureStorageProvider),
    ref.watch(realtimeServiceProvider),
  );

  ref.listen<bool>(
    sessionExpiredProvider,
    (previous, next) {
      if (next) {
        notifier.signOut();
        ref.read(sessionExpiredProvider.notifier).state = false;
      }
    },
  );

  notifier.restore();

  return notifier;
});
final directoryProvider = FutureProvider.family<List<User>, UserRole>(
    (ref, role) => ref.watch(appRepositoryProvider).users(role));
final userByIdProvider = FutureProvider.family<User, int>(
    (ref, id) => ref.watch(appRepositoryProvider).user(id));

final connectionsProvider = FutureProvider<List<ConnectionItem>>(
    (ref) => ref.watch(appRepositoryProvider).connections());
final notificationsProvider = FutureProvider<List<NotificationItem>>(
    (ref) => ref.watch(appRepositoryProvider).notifications());
final unreadNotificationCountProvider = FutureProvider<int>(
    (ref) => ref.watch(appRepositoryProvider).unreadCount());
final conversationsProvider = StateNotifierProvider<
    ConversationsPaginationNotifier, AsyncValue<List<Conversation>>>(
  (ref) => ConversationsPaginationNotifier(ref.watch(appRepositoryProvider)),
);
final allUsersProvider = FutureProvider<List<User>>(
    (ref) => ref.watch(appRepositoryProvider).allUsers());
final pendingUsersProvider = FutureProvider<List<User>>(
  (ref) => ref.watch(appRepositoryProvider).pendingUsers(),
);
final eventsProvider = StateNotifierProvider<EventsPaginationNotifier,
    AsyncValue<List<EventItem>>>(
  (ref) => EventsPaginationNotifier(
    ref.watch(appRepositoryProvider),
    all: false,
  ),
);

final adminEventsProvider = StateNotifierProvider<EventsPaginationNotifier,
    AsyncValue<List<EventItem>>>(
  (ref) => EventsPaginationNotifier(
    ref.watch(appRepositoryProvider),
    all: true,
  ),
);

class EventsPaginationNotifier
    extends StateNotifier<AsyncValue<List<EventItem>>> {
  EventsPaginationNotifier(
    this._repo, {
    required this.all,
  }) : super(const AsyncLoading()) {
    unawaited(loadFirstPage());
  }

  final AppRepository _repo;
  final bool all;

  static const int pageSize = 50;

  int _page = 0;
  bool _hasMore = true;
  bool _loadingMore = false;

  Future<void> loadFirstPage() async {
    _page = 0;
    _hasMore = true;
    _loadingMore = false;

    state = const AsyncLoading();

    try {
      final results = await _repo.events(
        all: all,
        page: 0,
        size: pageSize,
      );

      _hasMore = results.length == pageSize;
      state = AsyncData(results);
    } catch (error, stackTrace) {
      state = AsyncError(error, stackTrace);
    }
  }

  Future<void> loadNextPage() async {
    if (_loadingMore || !_hasMore) return;

    _loadingMore = true;

    final nextPage = _page + 1;

    try {
      final results = await _repo.events(
        all: all,
        page: nextPage,
        size: pageSize,
      );

      final current = state.valueOrNull ?? <EventItem>[];

      _page = nextPage;
      _hasMore = results.length == pageSize;

      state = AsyncData([
        ...current,
        ...results,
      ]);
    } catch (error, stackTrace) {
      // Keep the already-loaded events visible if loading another
      // page fails.
      if (state.hasValue) {
        state = AsyncData(state.value!);
      } else {
        state = AsyncError(error, stackTrace);
      }
    } finally {
      _loadingMore = false;
    }
  }
}

class ConversationsPaginationNotifier
    extends StateNotifier<AsyncValue<List<Conversation>>> {
  ConversationsPaginationNotifier(this._repo) : super(const AsyncLoading()) {
    unawaited(loadFirstPage());
  }

  final AppRepository _repo;
  static const int pageSize = 50;
  int _page = 0;
  bool _hasMore = true;
  bool _loadingMore = false;

  Future<void> loadFirstPage() async {
    _page = 0;
    _hasMore = true;
    _loadingMore = false;
    state = const AsyncLoading();
    try {
      final result = await _repo.conversations(page: 0, size: pageSize);
      _hasMore = result.length == pageSize;
      state = AsyncData(result);
    } catch (error, stackTrace) {
      state = AsyncError(error, stackTrace);
    }
  }

  Future<void> loadNextPage() async {
    if (_loadingMore || !_hasMore) return;
    _loadingMore = true;
    final nextPage = _page + 1;
    try {
      final result = await _repo.conversations(page: nextPage, size: pageSize);
      final current = state.valueOrNull ?? <Conversation>[];
      _page = nextPage;
      _hasMore = result.length == pageSize;
      state = AsyncData([...current, ...result]);
    } catch (error, stackTrace) {
      if (!state.hasValue) state = AsyncError(error, stackTrace);
    } finally {
      _loadingMore = false;
    }
  }
}

class AuthNotifier extends StateNotifier<AsyncValue<User?>> {
  AuthNotifier(this._repo, this._storage, this._realtime)
      : super(const AsyncLoading());
  final AppRepository _repo;
  final SecureStorageService _storage;
  final RealtimeService _realtime;

  void setUser(User user) => state = AsyncData(user);
  Future<void> retryRestore() async {
    state = const AsyncLoading();
    await restore();
  }

  Future<void> restore() async {
    // Hard ceiling: never leave the router in AsyncLoading indefinitely if
    // platform secure-storage stalls beyond Future.timeout (seen on emulators).
    final watchdog = Timer(const Duration(seconds: 3), () {
      if (state.isLoading) {
        state = const AsyncData(null);
      }
    });
    try {
      final token =
          await _storage.readToken().timeout(const Duration(seconds: 2));

      if (token == null || token.isEmpty) {
        state = const AsyncData(null);
        return;
      }

      final identity = _fromJwt(token);
      if (identity == null) {
        await _storage.clearAuth();
        state = const AsyncData(null);
        return;
      }
      final user = await _repo.userByEmail(identity.email);

      if (user.status.toUpperCase() != 'APPROVED') {
        await _storage.clearAuth();
        state = const AsyncData(null);
        return;
      }
      _realtime.connect(token);
      state = AsyncData(user);
    } catch (e, st) {
      _realtime.disconnect();
      state = AsyncError(e, st);
    } finally {
      watchdog.cancel();
    }
  }

  Future<String?> signIn(String email, String password, UserRole role) async {
    // Avoid AsyncLoading here: router treats loading as splash-only.
    try {
      final result = await _repo.login(email, password, role);
      if (result == 'WAIT_APPROVAL') {
        state = const AsyncData(null);
        return result;
      }
      if (result.contains('.')) {
        final identity = _fromJwt(result);
        if (identity == null) {
          state = const AsyncData(null);
          return 'Invalid credentials';
        }
        await _storage.writeToken(result);
        final user = await _repo.userByEmail(identity.email);
        if (user.status.toUpperCase() != 'APPROVED') {
          await _storage.clearAuth();
          state = const AsyncData(null);
          return 'Account is not approved';
        }
        _realtime.connect(result);
        state = AsyncData(user);
        return null;
      }
      state = const AsyncData(null);
      return result;
    } catch (error) {
      state = const AsyncData(null);
      final apiError = toApiException(error, requestPath: '/login');
      return apiError.message;
    }
  }

  Future<void> handleUnauthorized() async {
    await signOut();
  }

  Future<void> signOut() async {
    _realtime.disconnect();
    await _storage.clearAuth();
    state = const AsyncData(null);
  }

  /// Returns null when the JWT payload is missing email/role.
  User? _fromJwt(String token) {
    try {
      final chunk = base64Url.normalize(token.split('.')[1]);
      final data = jsonDecode(utf8.decode(base64Url.decode(chunk)))
          as Map<String, dynamic>;
      final email = data['sub'] as String? ?? '';
      final role = userRole(data['role'] as String?);
      if (email.isEmpty || role == UserRole.unknown) {
        return null;
      }
      return User(
          id: 0, name: '', email: email, role: role, status: 'APPROVED');
    } catch (_) {
      return null;
    }
  }
}
