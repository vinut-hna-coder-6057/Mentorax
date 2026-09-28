import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../shared/models/models.dart';
import 'providers.dart';
import '../features/presentation.dart';

final routerProvider = Provider<GoRouter>((ref) {
  final refresh = _RouterRefresh(ref);
  ref.onDispose(refresh.dispose);
  return GoRouter(
      initialLocation: '/splash',
      refreshListenable: refresh,
      redirect: (context, state) {
        final auth = ref.read(authProvider);

        final location = state.matchedLocation;
        // Splash is only valid while session restore is in flight.
        if (auth.hasError) {
          return location == '/splash' ? null : '/splash';
        }
        if (auth.isLoading) {
          return location == '/splash' ? null : '/splash';
        }
        final user = auth.valueOrNull;
        const publicRoutes = {
          '/login',
          '/register',
          '/forgot-password',
          '/reset-password',
          '/otp-verify',
          '/email-verification',
          '/pending-approval'
        };
        final loggedIn = user != null &&
            user.email.isNotEmpty &&
            user.role != UserRole.unknown;
        if (!loggedIn) {
          // Auth resolved with no usable session — never remain on splash
          // (SplashScreen is only a CircularProgressIndicator).
          if (location == '/splash' || !publicRoutes.contains(location)) {
            return '/login';
          }
          return null;
        }
        final home = user.role == UserRole.admin
            ? '/admin/dashboard'
            : '/${user.role.name}/home';
        if (location == '/splash' || publicRoutes.contains(location)) {
          return home;
        }
        if (location.startsWith('/admin') && user.role != UserRole.admin) {
          return home;
        }
        if ((location.startsWith('/student') &&
                user.role != UserRole.student) ||
            (location.startsWith('/alumni') && user.role != UserRole.alumni)) {
          return home;
        }
        return null;
      },
      routes: [
        GoRoute(path: '/splash', builder: (_, __) => const SplashScreen()),
        GoRoute(path: '/login', builder: (_, __) => const LoginScreen()),
        GoRoute(path: '/register', builder: (_, __) => const RegisterScreen()),
        GoRoute(
          path: '/email-verification',
          builder: (_, state) {
            final email = state.uri.queryParameters['email'] ?? '';
            final roleValue = state.uri.queryParameters['role'] ?? 'student';

            final role = UserRole.values.firstWhere(
              (r) => r.name == roleValue,
              orElse: () => UserRole.student,
            );

            return EmailVerificationScreen(
              email: email,
              role: role,
            );
          },
        ),
        GoRoute(
            path: '/forgot-password',
            builder: (_, __) => const PasswordScreen(forgot: true)),
        GoRoute(
            path: '/reset-password',
            builder: (_, state) => PasswordScreen(
                  email: state.uri.queryParameters['email'] ?? '',
                )),
        GoRoute(
            path: '/otp-verify',
            builder: (_, state) => OtpScreen(
                  email: state.uri.queryParameters['email'] ?? '',
                )),
        GoRoute(
            path: '/pending-approval',
            builder: (_, __) => const PendingApprovalScreen()),
        GoRoute(
            path: '/student/:tab',
            builder: (_, s) => RoleShell(
                role: UserRole.student, tab: s.pathParameters['tab']!)),
        GoRoute(
            path: '/alumni/:tab',
            builder: (_, s) => RoleShell(
                role: UserRole.alumni, tab: s.pathParameters['tab']!)),
        GoRoute(
            path: '/admin/:tab',
            builder: (_, s) =>
                RoleShell(role: UserRole.admin, tab: s.pathParameters['tab']!)),
        GoRoute(
            path: '/user/:id',
            builder: (_, s) =>
                _intRoute(s.pathParameters['id'], (id) => UserScreen(id: id))),
        GoRoute(
            path: '/event/:id',
            builder: (_, s) =>
                _intRoute(s.pathParameters['id'], (id) => EventScreen(id: id))),
        GoRoute(
            path: '/connections',
            builder: (_, __) => const ConnectionsScreen()),
        GoRoute(
            path: '/notifications',
            builder: (_, __) => const NotificationsScreen()),
        GoRoute(
            path: '/chat/:email',
            builder: (_, s) => ChatScreen(
                email: Uri.decodeComponent(s.pathParameters['email']!))),
      ]);
});

Widget _intRoute(String? value, Widget Function(int) builder) {
  final id = int.tryParse(value ?? '');
  return id == null ? const InvalidRouteScreen() : builder(id);
}

class _RouterRefresh extends ChangeNotifier {
  _RouterRefresh(Ref ref) {
    ref.listen<AsyncValue<User?>>(authProvider, (_, __) => notifyListeners());
  }
}
