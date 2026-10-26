import 'dart:convert';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../core/errors/api_exception.dart';
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
  ref.listen<AsyncValue<RealtimeSessionEvent<NotificationItem>>>(
    realtimeNotificationsProvider,
    (_, notification) {
      final event = notification.valueOrNull;
      if (event != null &&
          ref.read(realtimeServiceProvider).isCurrentSession(
                event.sessionVersion,
                event.connectionVersion,
              )) {
        ref.invalidate(notificationsProvider);
        ref.invalidate(unreadNotificationCountProvider);
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
final realtimeNotificationsProvider =
    StreamProvider<RealtimeSessionEvent<NotificationItem>>((ref) {
  final realtime = ref.watch(realtimeServiceProvider);
  return realtime.notificationEvents.where((event) => realtime.isCurrentSession(
        event.sessionVersion,
        event.connectionVersion,
      ));
});
final conversationsProvider = StateNotifierProvider.family<
    ConversationsPaginationNotifier, AsyncValue<List<Conversation>>, UserRole?>(
  (ref, participantRole) => ConversationsPaginationNotifier(
    ref.watch(appRepositoryProvider),
    participantRole: participantRole,
  ),
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
final eventRegistrationStatusProvider = FutureProvider.family<bool, int>(
  (ref, eventId) =>
      ref.watch(appRepositoryProvider).eventRegistrationStatus(eventId),
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
  ConversationsPaginationNotifier(
    this._repo, {
    required this.participantRole,
  }) : super(const AsyncLoading()) {
    unawaited(loadFirstPage());
  }

  final AppRepository _repo;
  final UserRole? participantRole;
  static const int pageSize = 50;
  int _page = 0;
  bool _hasMore = true;
  bool _loadingMore = false;
  int _requestGeneration = 0;

  bool get hasMore => _hasMore;
  bool get isLoadingMore => _loadingMore;

  Future<void> loadFirstPage() async {
    final generation = ++_requestGeneration;
    _page = 0;
    _hasMore = true;
    _loadingMore = false;
    state = const AsyncLoading();
    try {
      final result = await _repo.conversations(
        page: 0,
        size: pageSize,
        participantRole: participantRole,
      );
      if (generation != _requestGeneration) return;
      _hasMore = result.length == pageSize;
      state = AsyncData(result);
    } catch (error, stackTrace) {
      if (generation != _requestGeneration) return;
      state = AsyncError(error, stackTrace);
    }
  }

  Future<void> loadNextPage() async {
    if (_loadingMore || !_hasMore) return;
    _loadingMore = true;
    final generation = _requestGeneration;
    final nextPage = _page + 1;
    try {
      final result = await _repo.conversations(
        page: nextPage,
        size: pageSize,
        participantRole: participantRole,
      );
      if (generation != _requestGeneration) return;
      final current = state.valueOrNull ?? <Conversation>[];
      _page = nextPage;
      _hasMore = result.length == pageSize;
      final existingEmails =
          current.map((conversation) => conversation.email).toSet();
      state = AsyncData([
        ...current,
        ...result.where(
          (conversation) => existingEmails.add(conversation.email),
        ),
      ]);
    } catch (error, stackTrace) {
      if (generation != _requestGeneration) return;
      if (!state.hasValue) state = AsyncError(error, stackTrace);
    } finally {
      if (generation == _requestGeneration) _loadingMore = false;
    }
  }
}

class AuthNotifier extends StateNotifier<AsyncValue<User?>> {
  AuthNotifier(this._repo, this._storage, this._realtime)
      : super(const AsyncLoading());
  final AppRepository _repo;
  final SecureStorageService _storage;
  final RealtimeService _realtime;
  int _operationVersion = 0;
  Future<void> _storageTail = Future<void>.value();

  void setUser(User user) {
    _operationVersion++;
    state = AsyncData(user);
  }

  Future<void> retryRestore() async {
    state = const AsyncLoading();
    await restore();
  }

  Future<void> restore() async {
    final operation = ++_operationVersion;
    // Hard ceiling: never leave the router in AsyncLoading indefinitely if
    // platform secure-storage stalls beyond Future.timeout (seen on emulators).
    final watchdog = Timer(const Duration(seconds: 3), () {
      if (_isCurrentOperation(operation) && state.isLoading) {
        _operationVersion++;
        _realtime.disconnect();
        state = const AsyncData(null);
      }
    });
    try {
      final token = await _serializeStorage(_storage.readToken)
          .timeout(const Duration(seconds: 2));
      if (!_isCurrentOperation(operation)) return;

      if (token == null || token.isEmpty) {
        state = const AsyncData(null);
        return;
      }

      final identity = _fromJwt(token);
      if (identity == null) {
        await _clearStorageIfCurrent(operation);
        if (!_isCurrentOperation(operation)) return;
        state = const AsyncData(null);
        return;
      }
      final user = await _repo.userByEmail(identity.email);
      if (!_isCurrentOperation(operation)) return;

      if (user.status.toUpperCase() != 'APPROVED') {
        await _clearStorageIfCurrent(operation);
        if (!_isCurrentOperation(operation)) return;
        state = const AsyncData(null);
        return;
      }
      _realtime.connect(token);
      state = AsyncData(user);
    } catch (e, st) {
      if (!_isCurrentOperation(operation)) return;
      _operationVersion++;
      _realtime.disconnect();
      state = AsyncError(e, st);
    } finally {
      watchdog.cancel();
    }
  }

  Future<String?> signIn(String email, String password, UserRole role) async {
    // Avoid AsyncLoading here: router treats loading as splash-only.
    final operation = ++_operationVersion;
    var tokenStored = false;
    try {
      final result = await _repo.login(email, password, role);
      if (!_isCurrentOperation(operation)) return 'Sign-in was cancelled.';
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
        tokenStored = await _serializeStorage(() async {
          if (!_isCurrentOperation(operation)) return false;
          await _storage.writeToken(result);
          return _isCurrentOperation(operation);
        });
        if (!_isCurrentOperation(operation)) return 'Sign-in was cancelled.';
        if (!tokenStored) return 'Sign-in was cancelled.';
        _realtime.connect(result);
        final user = await _repo.userByEmail(identity.email);
        if (!_isCurrentOperation(operation)) return 'Sign-in was cancelled.';
        if (user.status.toUpperCase() != 'APPROVED') {
          _realtime.disconnect();
          await _clearStorageIfCurrent(operation);
          if (!_isCurrentOperation(operation)) return 'Sign-in was cancelled.';
          state = const AsyncData(null);
          return 'Account is not approved';
        }
        state = AsyncData(user);
        return null;
      }
      state = const AsyncData(null);
      return result;
    } catch (error) {
      if (!_isCurrentOperation(operation)) return 'Sign-in was cancelled.';
      _operationVersion++;
      if (tokenStored) {
        _realtime.disconnect();
        try {
          await _serializeStorage(_storage.clearAuth);
        } catch (cleanupError, stackTrace) {
          debugPrint(
            'Unable to clear authentication after sign-in failed: '
            '$cleanupError\n$stackTrace',
          );
        }
      }
      state = const AsyncData(null);
      final apiError = toApiException(error, requestPath: '/login');
      if (apiError.kind == ApiErrorKind.pendingApproval) {
        return 'WAIT_APPROVAL';
      }
      if (apiError.kind == ApiErrorKind.verificationRequired) {
        return 'VERIFY_EMAIL';
      }
      return apiError.message;
    }
  }

  Future<void> handleUnauthorized() async {
    await signOut();
  }

  Future<void> signOut() async {
    _operationVersion++;
    _realtime.disconnect();
    state = const AsyncData(null);
    await _serializeStorage(_storage.clearAuth);
  }

  bool _isCurrentOperation(int operation) => operation == _operationVersion;

  Future<T> _serializeStorage<T>(Future<T> Function() action) {
    final completer = Completer<T>();
    final operation = _storageTail.then((_) async {
      try {
        completer.complete(await action());
      } catch (error, stackTrace) {
        completer.completeError(error, stackTrace);
      }
    });
    _storageTail = operation.catchError((Object _) {});
    return completer.future;
  }

  Future<void> _clearStorageIfCurrent(int operation) =>
      _serializeStorage(() async {
        if (_isCurrentOperation(operation)) await _storage.clearAuth();
      });

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
