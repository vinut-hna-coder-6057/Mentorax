import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';

import '../app/providers.dart';
import '../core/errors/api_exception.dart';
import '../core/errors/error_handler.dart';
import '../core/network/realtime_service.dart';
import '../shared/models/models.dart';
import '../shared/widgets/ui_components.dart';

final _emailPattern = RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$');

bool _isValidEmail(String email) => _emailPattern.hasMatch(email.trim());

String registrationSuccessLocation(UserRole role) =>
    role == UserRole.alumni ? '/pending-approval' : '/login?registered=1';

class SplashScreen extends ConsumerWidget {
  const SplashScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authProvider);

    if (auth.hasError) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Unable to restore your session.'),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: () => ref.read(authProvider.notifier).retryRestore(),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
      );
    }

    return const Scaffold(
      body: LoadingState(),
    );
  }
}

class InvalidRouteScreen extends StatelessWidget {
  const InvalidRouteScreen({super.key});
  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: const Text('Not found')),
      body: const Center(child: Text('The requested resource is invalid.')));
}

class PendingApprovalScreen extends StatelessWidget {
  const PendingApprovalScreen({super.key});

  @override
  Widget build(BuildContext c) => Scaffold(
        body: SafeArea(
          child: responsiveContent(
            c,
            Padding(
              padding: const EdgeInsets.all(28),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.hourglass_top_rounded,
                    size: 64,
                    color: Theme.of(c).colorScheme.secondary,
                  ),
                  const SizedBox(height: 20),
                  Text(
                    'Approval in progress',
                    style: Theme.of(c).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 10),
                  const Text(
                    'Your account was created successfully and is waiting for administrator approval. You can return to sign in once it is active.',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 20),
                  const StatusBadge('PENDING'),
                  const SizedBox(height: 28),
                  FilledButton.icon(
                    onPressed: () => c.go('/login'),
                    icon: const Icon(Icons.login),
                    label: const Text('Back to sign in'),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

class ApprovalsScreen extends ConsumerStatefulWidget {
  const ApprovalsScreen({super.key});

  @override
  ConsumerState<ApprovalsScreen> createState() => _ApprovalsScreenState();
}

class _ApprovalsScreenState extends ConsumerState<ApprovalsScreen> {
  final _workingUserIds = <int>{};

  Future<void> _handleAction(User user, {required bool approve}) async {
    if (!_workingUserIds.add(user.id)) return;
    setState(() {});
    try {
      if (!approve) {
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (dialogContext) => AlertDialog(
            title: const Text('Reject alumni application?'),
            content: Text('Reject ${user.name}’s alumni application?'),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext, false),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(dialogContext, true),
                child: const Text('Reject application'),
              ),
            ],
          ),
        );
        if (!mounted || confirmed != true) return;
      }
      final repository = ref.read(appRepositoryProvider);
      if (approve) {
        await repository.approveAlumni(user.id);
      } else {
        await repository.rejectAlumni(user.id);
      }
      ref.invalidate(pendingUsersProvider);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(approve ? 'Alumni approved' : 'Alumni rejected'),
          ),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    } finally {
      if (mounted) setState(() => _workingUserIds.remove(user.id));
    }
  }

  @override
  Widget build(BuildContext c) {
    final users = ref.watch(pendingUsersProvider);
    final pendingCount = users.valueOrNull?.length;

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg,
            AppSpacing.md,
            AppSpacing.lg,
            0,
          ),
          child: Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    AppSectionTitle('Alumni approvals'),
                    SizedBox(height: AppSpacing.xs),
                    Text('Review and respond to pending applications.'),
                  ],
                ),
              ),
              if (pendingCount != null) StatusBadge('$pendingCount pending'),
            ],
          ),
        ),
        Expanded(
          child: users.when(
            loading: () => const LoadingState(),
            error: (error, _) => ErrorState(
              message: userFacingError(error),
              onRetry: () => ref.invalidate(pendingUsersProvider),
            ),
            data: (pendingUsers) {
              if (pendingUsers.isEmpty) {
                return const EmptyState(
                  title: 'No pending approvals',
                  message: 'New alumni applications will appear here.',
                  icon: Icons.verified_user_outlined,
                );
              }

              return ListView.builder(
                padding: const EdgeInsets.all(AppSpacing.lg),
                itemCount: pendingUsers.length,
                itemBuilder: (_, index) {
                  final u = pendingUsers[index];
                  final isWorking = _workingUserIds.contains(u.id);

                  return Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.md),
                    child: AppCard(
                      child: LayoutBuilder(
                        builder: (context, constraints) {
                          final compact = constraints.maxWidth < 420;
                          final person = Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              UserAvatar(
                                name: u.name,
                                imageUrl: u.profileImage,
                                radius: 26,
                              ),
                              const SizedBox(width: AppSpacing.md),
                              Expanded(
                                child: Column(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  children: [
                                    Text(
                                      u.name,
                                      style: Theme.of(context)
                                          .textTheme
                                          .titleMedium,
                                    ),
                                    const SizedBox(height: AppSpacing.xs),
                                    Text(
                                      [
                                        u.email,
                                        if (u.jobRole?.isNotEmpty == true)
                                          u.jobRole!,
                                        if (u.company?.isNotEmpty == true)
                                          u.company!,
                                        if (u.passoutYear?.isNotEmpty == true)
                                          'Class of ${u.passoutYear}',
                                      ].join(' · '),
                                      softWrap: true,
                                    ),
                                  ],
                                ),
                              ),
                              IconButton(
                                tooltip: 'View profile',
                                onPressed: () => c.push('/user/${u.id}'),
                                icon: const Icon(Icons.open_in_new),
                              ),
                            ],
                          );
                          final actions = Wrap(
                            alignment: compact
                                ? WrapAlignment.start
                                : WrapAlignment.end,
                            spacing: AppSpacing.sm,
                            runSpacing: AppSpacing.sm,
                            children: [
                              OutlinedButton.icon(
                                onPressed: isWorking
                                    ? null
                                    : () => _handleAction(u, approve: false),
                                icon: const Icon(Icons.close),
                                label: const Text('Reject'),
                              ),
                              FilledButton.icon(
                                onPressed: isWorking
                                    ? null
                                    : () => _handleAction(u, approve: true),
                                icon: isWorking
                                    ? const SizedBox(
                                        width: 16,
                                        height: 16,
                                        child: CircularProgressIndicator(
                                          strokeWidth: 2,
                                        ),
                                      )
                                    : const Icon(Icons.check),
                                label:
                                    Text(isWorking ? 'Updating…' : 'Approve'),
                              ),
                            ],
                          );
                          return Padding(
                            padding: const EdgeInsets.all(AppSpacing.sm),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.stretch,
                              children: [
                                person,
                                const SizedBox(height: AppSpacing.md),
                                actions,
                              ],
                            ),
                          );
                        },
                      ),
                    ),
                  );
                },
              );
            },
          ),
        ),
      ],
    );
  }
}

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key, this.signupComplete = false});
  final bool signupComplete;

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _email = TextEditingController();
  final _pass = TextEditingController();
  UserRole _role = UserRole.student;
  String? _error;
  bool _busy = false;
  bool _showPassword = false;
  final _formKey = GlobalKey<FormState>();

  @override
  void dispose() {
    _email.dispose();
    _pass.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        body: SafeArea(
            child: Padding(
                padding: const EdgeInsets.all(24),
                child: LayoutBuilder(builder: (context, constraints) {
                  return SingleChildScrollView(
                      child: ConstrainedBox(
                          constraints:
                              BoxConstraints(minHeight: constraints.maxHeight),
                          child: Center(
                            child: SizedBox(
                              width: constraints.maxWidth < 520
                                  ? constraints.maxWidth
                                  : 520,
                              child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  crossAxisAlignment:
                                      CrossAxisAlignment.stretch,
                                  children: [
                                    Form(
                                      key: _formKey,
                                      child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.stretch,
                                          children: [
                                            Icon(
                                              Icons.hub_rounded,
                                              size: 54,
                                              color: Theme.of(context)
                                                  .colorScheme
                                                  .primary,
                                            ),
                                            const SizedBox(height: 8),
                                            Text('Welcome back',
                                                style: Theme.of(context)
                                                    .textTheme
                                                    .headlineMedium,
                                                textAlign: TextAlign.center),
                                            Text(
                                              'Connect, grow, and give back.',
                                              style: Theme.of(context)
                                                  .textTheme
                                                  .bodyMedium,
                                              textAlign: TextAlign.center,
                                            ),
                                            if (widget.signupComplete) ...[
                                              const SizedBox(height: 12),
                                              const Text(
                                                'Your account was created. Sign in to continue.',
                                                textAlign: TextAlign.center,
                                              ),
                                            ],
                                            const SizedBox(height: 16),
                                            TextFormField(
                                                controller: _email,
                                                keyboardType:
                                                    TextInputType.emailAddress,
                                                textInputAction:
                                                    TextInputAction.next,
                                                autofillHints: const [
                                                  AutofillHints.username,
                                                  AutofillHints.email,
                                                ],
                                                validator: (value) {
                                                  if (value == null ||
                                                      value.trim().isEmpty) {
                                                    return 'Enter your email address.';
                                                  }
                                                  if (!_isValidEmail(value)) {
                                                    return 'Enter a valid email address.';
                                                  }
                                                  return null;
                                                },
                                                decoration:
                                                    const InputDecoration(
                                                        labelText: 'Email',
                                                        prefixIcon: Icon(Icons
                                                            .email_outlined)),
                                                onChanged: (_) {
                                                  if (_error != null) {
                                                    setState(
                                                        () => _error = null);
                                                  }
                                                }),
                                            TextFormField(
                                                controller: _pass,
                                                obscureText: !_showPassword,
                                                textInputAction:
                                                    TextInputAction.done,
                                                autofillHints: const [
                                                  AutofillHints.password,
                                                ],
                                                validator: (value) =>
                                                    value == null || value.isEmpty
                                                        ? 'Enter your password.'
                                                        : null,
                                                decoration: InputDecoration(
                                                    labelText: 'Password',
                                                    prefixIcon: const Icon(
                                                        Icons.lock_outline),
                                                    suffixIcon: IconButton(
                                                        tooltip: _showPassword
                                                            ? 'Hide password'
                                                            : 'Show password',
                                                        onPressed: () => setState(
                                                            () => _showPassword =
                                                                !_showPassword),
                                                        icon: Icon(_showPassword
                                                            ? Icons
                                                                .visibility_off_outlined
                                                            : Icons.visibility_outlined))),
                                                onFieldSubmitted: (_) => _submit()),
                                            const SizedBox(height: 8),
                                            DropdownButtonFormField<UserRole>(
                                                initialValue: _role,
                                                decoration:
                                                    const InputDecoration(
                                                        labelText: 'Sign in as',
                                                        prefixIcon: Icon(Icons
                                                            .badge_outlined)),
                                                items: const [
                                                  DropdownMenuItem(
                                                      value: UserRole.student,
                                                      child: Text('Student')),
                                                  DropdownMenuItem(
                                                      value: UserRole.alumni,
                                                      child: Text('Alumni')),
                                                  DropdownMenuItem(
                                                      value: UserRole.admin,
                                                      child: Text('Admin')),
                                                ],
                                                onChanged: _busy
                                                    ? null
                                                    : (v) {
                                                        if (v != null) {
                                                          setState(
                                                              () => _role = v);
                                                        }
                                                      }),
                                            if (_error != null) ...[
                                              const SizedBox(height: 8),
                                              InlineError(_error!),
                                            ],
                                            const SizedBox(height: 12),
                                            FilledButton(
                                                onPressed:
                                                    _busy ? null : _submit,
                                                child: Text(_busy
                                                    ? 'Signing in…'
                                                    : 'Sign in')),
                                            TextButton(
                                                onPressed: () =>
                                                    context.go('/register'),
                                                child: const Text(
                                                    'Create account')),
                                            TextButton(
                                                onPressed: () => context
                                                    .go('/forgot-password'),
                                                child: const Text(
                                                    'Forgot password?'))
                                          ]),
                                    ),
                                  ]),
                            ),
                          )));
                }))));
  }

  Future<void> _submit() async {
    final email = _email.text.trim();
    if (!_formKey.currentState!.validate()) {
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final x = await ref
          .read(authProvider.notifier)
          .signIn(email, _pass.text, _role);
      if (!mounted) return;
      if (x == 'WAIT_APPROVAL') {
        context.go('/pending-approval');
      } else {
        setState(() => _error = x);
      }
    } catch (error) {
      if (mounted) setState(() => _error = userFacingError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

class RegisterScreen extends StatefulWidget {
  const RegisterScreen({super.key});
  @override
  State<RegisterScreen> createState() => _RegisterScreenState();
}

class _RegisterScreenState extends State<RegisterScreen> {
  final _name = TextEditingController();
  final _email = TextEditingController();
  final _password = TextEditingController();
  UserRole _role = UserRole.student;
  String? _error;
  bool _busy = false;
  bool _showPassword = false;
  bool _showSignInRecovery = false;
  final _formKey = GlobalKey<FormState>();

  @override
  void dispose() {
    _name.dispose();
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) {
    return Scaffold(
        appBar: AppBar(),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: responsiveContent(
                c,
                Form(
                    key: _formKey,
                    child: Column(children: [
                      Icon(
                        Icons.hub_rounded,
                        size: 48,
                        color: Theme.of(c).colorScheme.primary,
                      ),
                      const SizedBox(height: 8),
                      Text('Create your account',
                          style: Theme.of(c).textTheme.headlineMedium),
                      const SizedBox(height: 8),
                      const Text(
                        'Join your alumni community and keep growing together.',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 20),
                      TextFormField(
                          controller: _name,
                          textCapitalization: TextCapitalization.words,
                          textInputAction: TextInputAction.next,
                          validator: (value) =>
                              value == null || value.trim().isEmpty
                                  ? 'Enter your full name.'
                                  : null,
                          decoration: const InputDecoration(
                              labelText: 'Full name',
                              prefixIcon: Icon(Icons.person_outline))),
                      const SizedBox(height: 12),
                      TextFormField(
                          controller: _email,
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: TextInputAction.next,
                          autofillHints: const [
                            AutofillHints.username,
                            AutofillHints.email,
                          ],
                          validator: (value) =>
                              value == null || !_isValidEmail(value)
                                  ? 'Enter a valid email address.'
                                  : null,
                          decoration: const InputDecoration(
                              labelText: 'Email',
                              prefixIcon: Icon(Icons.email_outlined))),
                      const SizedBox(height: 12),
                      TextFormField(
                          controller: _password,
                          obscureText: !_showPassword,
                          textInputAction: TextInputAction.done,
                          autofillHints: const [AutofillHints.newPassword],
                          validator: (value) =>
                              value == null || value.length < 8
                                  ? 'Use a password of at least 8 characters.'
                                  : null,
                          decoration: InputDecoration(
                              labelText: 'Password',
                              helperText: 'Use at least 8 characters',
                              prefixIcon: const Icon(Icons.lock_outline),
                              suffixIcon: IconButton(
                                  onPressed: () => setState(
                                      () => _showPassword = !_showPassword),
                                  icon: Icon(_showPassword
                                      ? Icons.visibility_off_outlined
                                      : Icons.visibility_outlined)))),
                      const SizedBox(height: 12),
                      DropdownButtonFormField<UserRole>(
                          initialValue: _role,
                          decoration: const InputDecoration(
                              labelText: 'I’m joining as',
                              prefixIcon: Icon(Icons.groups_outlined)),
                          items: const [
                            DropdownMenuItem(
                                value: UserRole.student,
                                child: Text('Student')),
                            DropdownMenuItem(
                                value: UserRole.alumni, child: Text('Alumni'))
                          ],
                          onChanged: _busy
                              ? null
                              : (value) {
                                  if (value != null) {
                                    setState(() => _role = value);
                                  }
                                }),
                      if (_error != null)
                        Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: InlineError(_error!)),
                      if (_showSignInRecovery)
                        TextButton(
                          onPressed: _busy ? null : () => c.go('/login'),
                          child: const Text('Go to sign in'),
                        ),
                      FilledButton(
                          onPressed: _busy
                              ? null
                              : () async {
                                  if (!_formKey.currentState!.validate()) {
                                    return;
                                  }
                                  setState(() {
                                    _busy = true;
                                    _error = null;
                                    _showSignInRecovery = false;
                                  });
                                  try {
                                    await ProviderScope.containerOf(c)
                                        .read(appRepositoryProvider)
                                        .signup(
                                            User(
                                                id: 0,
                                                name: _name.text.trim(),
                                                email: _email.text.trim(),
                                                role: _role,
                                                status: 'PENDING'),
                                            _password.text);
                                    if (!c.mounted) return;

                                    c.go(registrationSuccessLocation(_role));
                                  } catch (error) {
                                    if (!c.mounted) return;
                                    final apiError = toApiException(
                                      error,
                                      requestPath: '/signup',
                                    );
                                    final canResume = {
                                      ApiErrorKind.timeout,
                                      ApiErrorKind.network,
                                      ApiErrorKind.server,
                                      ApiErrorKind.conflict,
                                    }.contains(apiError.kind);
                                    setState(() {
                                      _busy = false;
                                      _showSignInRecovery = canResume;
                                      _error = apiError.kind ==
                                                  ApiErrorKind.timeout ||
                                              apiError.kind ==
                                                  ApiErrorKind.network ||
                                              apiError.kind ==
                                                  ApiErrorKind.server
                                          ? "We couldn't confirm signup. Your account may have been created. Try signing in before registering again."
                                          : apiError.message;
                                    });
                                  }
                                },
                          child: Text(
                            _busy ? 'Creating account…' : 'Create account',
                          ))
                    ]))),
          ),
        ));
  }
}

class PasswordScreen extends StatefulWidget {
  const PasswordScreen({
    super.key,
    this.forgot = false,
    this.email = '',
    this.resetToken = '',
  });
  final bool forgot;
  final String email;
  final String resetToken;

  @override
  State<PasswordScreen> createState() => _PasswordScreenState();
}

class _PasswordScreenState extends State<PasswordScreen> {
  late final TextEditingController _email;
  final _password = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _busy = false;
  bool _showPassword = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _email = TextEditingController(text: widget.email);
  }

  @override
  void dispose() {
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) {
    return Scaffold(
        appBar: AppBar(),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: responsiveContent(
                c,
                Form(
                    key: _formKey,
                    child: Column(children: [
                      Text(
                        widget.forgot
                            ? 'Reset your password'
                            : 'Choose a new password',
                        style: Theme.of(c).textTheme.headlineSmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        widget.forgot
                            ? 'We’ll send a password reset code to your email.'
                            : 'Enter your account email and choose a new password.',
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 20),
                      TextFormField(
                          controller: _email,
                          keyboardType: TextInputType.emailAddress,
                          textInputAction: widget.forgot
                              ? TextInputAction.done
                              : TextInputAction.next,
                          readOnly: !widget.forgot && widget.email.isNotEmpty,
                          autofillHints: const [
                            AutofillHints.username,
                            AutofillHints.email,
                          ],
                          validator: (value) =>
                              value == null || !_isValidEmail(value)
                                  ? 'Enter a valid email address.'
                                  : null,
                          decoration: const InputDecoration(
                              labelText: 'Email',
                              prefixIcon: Icon(Icons.email_outlined))),
                      if (!widget.forgot) ...[
                        const SizedBox(height: 12),
                        TextFormField(
                            controller: _password,
                            obscureText: !_showPassword,
                            textInputAction: TextInputAction.done,
                            autofillHints: const [AutofillHints.newPassword],
                            validator: (value) =>
                                value == null || value.length < 8
                                    ? 'Use a password of at least 8 characters.'
                                    : null,
                            decoration: InputDecoration(
                                labelText: 'New password',
                                helperText: 'Use at least 8 characters',
                                prefixIcon: const Icon(Icons.lock_outline),
                                suffixIcon: IconButton(
                                  tooltip: _showPassword
                                      ? 'Hide password'
                                      : 'Show password',
                                  onPressed: () => setState(
                                      () => _showPassword = !_showPassword),
                                  icon: Icon(_showPassword
                                      ? Icons.visibility_off_outlined
                                      : Icons.visibility_outlined),
                                ))),
                      ],
                      if (_error != null) ...[
                        const SizedBox(height: 8),
                        InlineError(_error!)
                      ],
                      const SizedBox(height: 12),
                      FilledButton(
                          onPressed: _busy ? null : _submit,
                          child: Text(_busy
                              ? 'Working…'
                              : widget.forgot
                                  ? 'Send OTP'
                                  : 'Reset password'))
                    ]))),
          ),
        ));
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _busy = true;
      _error = null;
    });

    try {
      final repo =
          ProviderScope.containerOf(context).read(appRepositoryProvider);
      if (widget.forgot) {
        await repo.forgotPassword(_email.text.trim());
      } else {
        await repo.resetPassword(
            _email.text.trim(), _password.text, widget.resetToken);
      }

      if (!mounted) return;
      final email = Uri.encodeComponent(_email.text.trim());
      context.go(widget.forgot ? '/otp-verify?email=$email' : '/login');
    } catch (error) {
      if (mounted) {
        setState(() => _error = userFacingError(error));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}

class OtpScreen extends StatefulWidget {
  const OtpScreen({super.key, this.email = ''});
  final String email;

  @override
  State<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends State<OtpScreen> {
  late final TextEditingController _email;
  final _otp = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _email = TextEditingController(text: widget.email);
  }

  @override
  void dispose() {
    _email.dispose();
    _otp.dispose();
    super.dispose();
  }

  Future<void> _verifyOtp() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final email = _email.text.trim();
      final resetToken = await ProviderScope.containerOf(context)
          .read(appRepositoryProvider)
          .verifyOtp(email, _otp.text.trim());

      if (!mounted) return;

      context.go('/reset-password?email=${Uri.encodeComponent(email)}',
          extra: resetToken);
    } catch (error) {
      if (!mounted) return;

      setState(() => _error = userFacingError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: responsiveContent(
            context,
            Form(
              key: _formKey,
              child: Column(
                children: [
                  Text(
                    'Verify your identity',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'If an account exists, a password reset code will be sent shortly. Enter it here when it arrives.',
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 20),
                  TextFormField(
                    controller: _email,
                    keyboardType: TextInputType.emailAddress,
                    readOnly: widget.email.isNotEmpty,
                    validator: (value) => value == null || !_isValidEmail(value)
                        ? 'Enter a valid email address.'
                        : null,
                    decoration: const InputDecoration(
                      labelText: 'Email',
                      prefixIcon: Icon(Icons.email_outlined),
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _otp,
                    keyboardType: TextInputType.number,
                    maxLength: 6,
                    textInputAction: TextInputAction.done,
                    validator: (value) => value == null ||
                            !RegExp(r'^\d{6}$').hasMatch(value.trim())
                        ? 'Enter the 6-digit code from your email.'
                        : null,
                    decoration: const InputDecoration(
                      labelText: 'Verification code',
                      prefixIcon: Icon(Icons.pin_outlined),
                      counterText: '',
                    ),
                    onFieldSubmitted: (_) => _busy ? null : _verifyOtp(),
                  ),
                  if (_error != null) ...[
                    const SizedBox(height: 8),
                    InlineError(_error!),
                  ],
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton(
                      onPressed: _busy ? null : _verifyOtp,
                      child: Text(_busy ? 'Verifying…' : 'Verify code'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class RoleShell extends ConsumerWidget {
  const RoleShell({super.key, required this.role, required this.tab});
  final UserRole role;
  final String tab;
  @override
  Widget build(BuildContext c, WidgetRef r) {
    final tabs = role == UserRole.admin
        ? ['dashboard', 'approvals', 'events', 'profile']
        : ['home', 'directory', 'events', 'profile'];
    final index = tabs.contains(tab) ? tabs.indexOf(tab) : 0;
    final pages = [
      const HomeScreen(),
      role == UserRole.admin
          ? const ApprovalsScreen()
          : const DirectoryScreen(),
      const EventsScreen(),
      const ProfileScreen()
    ];
    final labels = role == UserRole.admin
        ? ['Dashboard', 'Approvals', 'Events', 'Profile']
        : ['Home', 'Directory', 'Events', 'Profile'];
    final icons = role == UserRole.admin
        ? [
            Icons.dashboard_outlined,
            Icons.verified_user_outlined,
            Icons.event_outlined,
            Icons.person_outline
          ]
        : [
            Icons.home_outlined,
            Icons.people_outline,
            Icons.event_outlined,
            Icons.person_outline
          ];
    final wide = MediaQuery.sizeOf(c).width >= 900;
    final destinations = [
      for (var i = 0; i < tabs.length; i++)
        NavigationDestination(icon: Icon(icons[i]), label: labels[i])
    ];

    return Scaffold(
      appBar: AppBar(
        title: Text(role == UserRole.admin ? 'Mentorax Admin' : 'Mentorax'),
        actions: [
          IconButton(
            tooltip: 'Notifications',
            onPressed: () => c.push('/notifications'),
            icon: const Icon(Icons.notifications_outlined),
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
      ),
      body: wide
          ? Row(
              children: [
                NavigationRail(
                  selectedIndex: index,
                  destinations: [
                    for (var i = 0; i < tabs.length; i++)
                      NavigationRailDestination(
                        icon: Icon(icons[i]),
                        label: Text(labels[i]),
                      ),
                  ],
                  onDestinationSelected: (i) =>
                      c.go('/${role.name}/${tabs[i]}'),
                ),
                const VerticalDivider(width: 1),
                Expanded(child: pages[index]),
              ],
            )
          : pages[index],
      bottomNavigationBar: wide
          ? null
          : NavigationBar(
              selectedIndex: index,
              destinations: destinations,
              onDestinationSelected: (i) => c.go('/${role.name}/${tabs[i]}'),
            ),
    );
  }
}

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});
  @override
  Widget build(BuildContext c, WidgetRef r) {
    final user = r.watch(authProvider).valueOrNull;
    final connections = r.watch(connectionsProvider);
    final events = user?.role == UserRole.admin
        ? r.watch(adminEventsProvider)
        : r.watch(eventsProvider);
    final unread = r.watch(unreadNotificationCountProvider);
    final pendingUsers =
        user?.role == UserRole.admin ? r.watch(pendingUsersProvider) : null;
    final greeting = switch (user?.role) {
      UserRole.student =>
        'Grow your network, ${user?.name.split(' ').first ?? 'there'}',
      UserRole.alumni =>
        'Welcome back, ${user?.name.split(' ').first ?? 'there'}',
      UserRole.admin => 'Admin overview',
      _ => 'Your community awaits',
    };
    final width = MediaQuery.sizeOf(c).width;

    return ListView(
      padding: EdgeInsets.all(width >= 900 ? 32 : 20),
      children: [
        Text(greeting, style: Theme.of(c).textTheme.headlineMedium),
        const SizedBox(height: AppSpacing.sm),
        Text(
          user?.role == UserRole.admin
              ? 'Review activity and keep the Mentorax community moving.'
              : 'Build meaningful connections with your alumni community.',
        ),
        const SizedBox(height: AppSpacing.xl),
        LayoutBuilder(
          builder: (context, constraints) {
            final columns = constraints.maxWidth >= 1100
                ? 4
                : constraints.maxWidth >= 520
                    ? 2
                    : 1;
            final items = [
              _DashboardMetric(
                label: 'Connections',
                value: connections.when(
                  data: (value) => '${value.where(
                        (connection) =>
                            connection.status.toUpperCase() == 'ACCEPTED',
                      ).length}',
                  loading: () => '…',
                  error: (_, __) => '—',
                ),
                icon: Icons.people_outline,
                onTap: () => c.push('/connections'),
              ),
              _DashboardMetric(
                label: 'Events',
                value: events.when(
                  data: (value) => '${value.length}',
                  loading: () => '…',
                  error: (_, __) => '—',
                ),
                icon: Icons.event_outlined,
                onTap: () => c.go('/${user?.role.name ?? 'student'}/events'),
              ),
              _DashboardMetric(
                label: 'Unread notifications',
                value: unread.when(
                  data: (value) => '$value',
                  loading: () => '…',
                  error: (_, __) => '—',
                ),
                icon: Icons.notifications_none,
                onTap: () => c.push('/notifications'),
              ),
              if (pendingUsers != null)
                _DashboardMetric(
                  label: 'Pending alumni',
                  value: pendingUsers.when(
                    data: (value) => '${value.length}',
                    loading: () => '…',
                    error: (_, __) => '—',
                  ),
                  icon: Icons.verified_user_outlined,
                  onTap: () => c.go('/admin/approvals'),
                ),
            ];
            return GridView.count(
              crossAxisCount: columns,
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              crossAxisSpacing: AppSpacing.md,
              mainAxisSpacing: AppSpacing.md,
              childAspectRatio: columns == 1 ? 3.3 : 2.0,
              children: items,
            );
          },
        ),
        const SizedBox(height: AppSpacing.xl),
        AppSectionTitle(
          'Community events',
          action: TextButton(
            onPressed: () => c.go('/${user?.role.name ?? 'student'}/events'),
            child: const Text('View all'),
          ),
        ),
        if (connections.hasError)
          ErrorState(
            title: 'Connections are unavailable',
            message: userFacingError(connections.error!),
            onRetry: () => r.invalidate(connectionsProvider),
          ),
        if (unread.hasError)
          ErrorState(
            title: 'Notifications are unavailable',
            message: userFacingError(unread.error!),
            onRetry: () => r.invalidate(unreadNotificationCountProvider),
          ),
        if (pendingUsers?.hasError == true)
          ErrorState(
            title: 'Approval data is unavailable',
            message: userFacingError(pendingUsers!.error!),
            onRetry: () => r.invalidate(pendingUsersProvider),
          ),
        const SizedBox(height: AppSpacing.sm),
        events.when(
          loading: () => const LoadingState(),
          error: (error, _) => ErrorState(
            message: userFacingError(error),
            onRetry: () => r.invalidate(user?.role == UserRole.admin
                ? adminEventsProvider
                : eventsProvider),
          ),
          data: (items) {
            if (items.isEmpty) {
              return const EmptyState(
                title: 'No events yet',
                message: 'New community events will appear here.',
                icon: Icons.event_available_outlined,
              );
            }
            return Column(
              children: [
                for (final event in items.take(3))
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                    child: AppCard(
                      child: ListTile(
                        leading: const Icon(Icons.event_outlined),
                        title: Text(event.title),
                        subtitle: Text([
                          if (event.eventDate?.isNotEmpty == true)
                            event.eventDate!,
                          if (event.location?.isNotEmpty == true)
                            event.location!,
                        ].join(' · ')),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => c.push('/event/${event.id}'),
                      ),
                    ),
                  ),
              ],
            );
          },
        ),
        const SizedBox(height: AppSpacing.xl),
        const AppSectionTitle('Your next steps'),
        const SizedBox(height: AppSpacing.sm),
        Wrap(
          spacing: AppSpacing.md,
          runSpacing: AppSpacing.md,
          children: [
            if (user?.role != UserRole.admin)
              _QuickAction(
                icon: Icons.person_search_outlined,
                label: 'Discover people',
                onTap: () => c.go('/${user?.role.name ?? 'student'}/directory'),
              ),
            _QuickAction(
              icon: Icons.chat_bubble_outline,
              label: 'Open messages',
              onTap: () => c.push('/chat/inbox'),
            ),
            if (user?.role == UserRole.admin)
              _QuickAction(
                icon: Icons.verified_user_outlined,
                label: 'Review approvals',
                onTap: () => c.go('/admin/approvals'),
              ),
          ],
        ),
      ],
    );
  }
}

class _DashboardMetric extends StatelessWidget {
  const _DashboardMetric({
    required this.label,
    required this.value,
    required this.icon,
    required this.onTap,
  });

  final String label;
  final String value;
  final IconData icon;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => AppCard(
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(AppRadius.md),
          child: Row(
            children: [
              Icon(icon,
                  color: Theme.of(context).colorScheme.primary, size: 28),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(value, style: Theme.of(context).textTheme.titleLarge),
                    Text(label),
                  ],
                ),
              ),
              const Icon(Icons.arrow_forward_ios, size: 14),
            ],
          ),
        ),
      );
}

class _QuickAction extends StatelessWidget {
  const _QuickAction(
      {required this.icon, required this.label, required this.onTap});
  final IconData icon;
  final String label;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) => SizedBox(
        width: 180,
        child: AppCard(
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(AppRadius.md),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(icon,
                    color: Theme.of(context).colorScheme.primary, size: 28),
                const SizedBox(height: AppSpacing.sm),
                Text(label,
                    style: const TextStyle(fontWeight: FontWeight.w700)),
              ],
            ),
          ),
        ),
      );
}

class DirectoryScreen extends ConsumerStatefulWidget {
  const DirectoryScreen({super.key});

  @override
  ConsumerState<DirectoryScreen> createState() => _DirectoryScreenState();
}

class _DirectoryScreenState extends ConsumerState<DirectoryScreen> {
  UserRole _role = UserRole.alumni;
  final _search = TextEditingController();
  final _scrollController = ScrollController();

  final List<User> _users = [];

  int _page = 0;
  static const int _pageSize = 50;

  bool _isLoading = true;
  bool _isLoadingMore = false;
  bool _hasMore = true;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _loadFirstPage();
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    _search.dispose();
    super.dispose();
  }

  Future<void> _loadFirstPage() async {
    if (!mounted) return;

    setState(() {
      _users.clear();
      _page = 0;
      _hasMore = true;
      _isLoading = true;
      _isLoadingMore = false;
      _error = null;
    });

    try {
      final results = await ref.read(appRepositoryProvider).users(
            _role,
            page: 0,
            size: _pageSize,
          );

      if (!mounted) return;

      setState(() {
        _users.addAll(results);
        _hasMore = results.length == _pageSize;
        _isLoading = false;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _error = error;
        _isLoading = false;
      });
    }
  }

  Future<void> _loadNextPage() async {
    if (!mounted || _isLoading || _isLoadingMore || !_hasMore) {
      return;
    }

    setState(() {
      _isLoadingMore = true;
    });

    final nextPage = _page + 1;

    try {
      final results = await ref.read(appRepositoryProvider).users(
            _role,
            page: nextPage,
            size: _pageSize,
          );

      if (!mounted) return;

      setState(() {
        _users.addAll(results);
        _page = nextPage;
        _hasMore = results.length == _pageSize;
        _isLoadingMore = false;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _isLoadingMore = false;
        _error = error;
      });
    }
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;

    final position = _scrollController.position;

    if (position.pixels >= position.maxScrollExtent - 300) {
      _loadNextPage();
    }
  }

  @override
  Widget build(BuildContext c) {
    final query = _search.text.trim().toLowerCase();

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1000),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Discover people',
                style: Theme.of(c).textTheme.headlineSmall,
              ),
              const SizedBox(height: 4),
              Text(
                'Find students and alumni and grow your professional network.',
                style: Theme.of(c).textTheme.bodyMedium,
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _search,
                textInputAction: TextInputAction.search,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: 'Search people',
                  prefixIcon: const Icon(Icons.search),
                  suffixIcon: query.isEmpty
                      ? null
                      : IconButton(
                          tooltip: 'Clear search',
                          onPressed: () {
                            _search.clear();
                            setState(() {});
                          },
                          icon: const Icon(Icons.close),
                        ),
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<UserRole>(
                initialValue: _role,
                decoration: const InputDecoration(
                  labelText: 'Browse',
                  prefixIcon: Icon(Icons.filter_list),
                ),
                items: const [
                  DropdownMenuItem(
                    value: UserRole.alumni,
                    child: Text('Alumni'),
                  ),
                  DropdownMenuItem(
                    value: UserRole.student,
                    child: Text('Students'),
                  ),
                ],
                onChanged: (value) {
                  if (value == null || value == _role) return;

                  setState(() {
                    _role = value;
                  });

                  _loadFirstPage();
                },
              ),
              const SizedBox(height: 12),
              Expanded(
                child: _isLoading
                    ? const LoadingState()
                    : _error != null && _users.isEmpty
                        ? ErrorState(
                            message: userFacingError(_error!),
                            onRetry: _loadFirstPage,
                          )
                        : _buildUserGrid(c, query),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildUserGrid(BuildContext c, String query) {
    final filtered = _users.where((user) {
      if (query.isEmpty) return true;

      final haystack = [
        user.name,
        user.email,
        user.company,
        user.jobRole,
        user.branch,
        user.location,
        user.skills,
      ].whereType<String>().join(' ').toLowerCase();

      return haystack.contains(query);
    }).toList();

    if (filtered.isEmpty) {
      return EmptyState(
        title: query.isEmpty
            ? 'No ${_role.name}s to show'
            : 'No matches found in loaded users',
        message: query.isEmpty
            ? 'People will appear here when they join the community.'
            : 'Try another name, skill, company, or location.',
        icon: Icons.person_search_outlined,
      );
    }

    return GridView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 480,
        mainAxisExtent: 124,
        crossAxisSpacing: AppSpacing.md,
        mainAxisSpacing: AppSpacing.md,
      ),
      itemCount: filtered.length + (_isLoadingMore ? 1 : 0),
      itemBuilder: (context, index) {
        if (index >= filtered.length) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(AppSpacing.md),
              child: CircularProgressIndicator(),
            ),
          );
        }

        final u = filtered[index];

        final details = [
          if (u.jobRole?.isNotEmpty == true) u.jobRole!,
          if (u.company?.isNotEmpty == true) u.company!,
          if (u.branch?.isNotEmpty == true) u.branch!,
          if (u.passoutYear?.isNotEmpty == true) 'Class of ${u.passoutYear}',
          if (u.location?.isNotEmpty == true) u.location!,
        ];

        return AppCard(
          child: InkWell(
            onTap: () => c.push('/user/${u.id}'),
            borderRadius: BorderRadius.circular(AppRadius.lg),
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Row(
                children: [
                  UserAvatar(
                    name: u.name,
                    imageUrl: u.profileImage,
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          u.name,
                          style: Theme.of(context).textTheme.titleMedium,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: AppSpacing.xs),
                        Text(
                          details.isEmpty ? u.email : details.join(' · '),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: AppSpacing.xs),
                  const Icon(Icons.chevron_right),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class EventsScreen extends ConsumerStatefulWidget {
  const EventsScreen({super.key});

  @override
  ConsumerState<EventsScreen> createState() => _EventsScreenState();
}

class _EventsScreenState extends ConsumerState<EventsScreen> {
  final _workingEventIds = <int>{};

  Future<void> _runAdminAction(
    BuildContext context,
    int eventId,
    String successMessage,
    Future<void> Function() action,
  ) async {
    if (!_workingEventIds.add(eventId)) return;
    setState(() {});
    try {
      await action();
      if (!context.mounted) return;
      ref.invalidate(adminEventsProvider);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(successMessage)),
      );
    } catch (error) {
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _workingEventIds.remove(eventId));
      }
    }
  }

  @override
  Widget build(BuildContext c) {
    final r = ref;
    final user = r.watch(authProvider).valueOrNull;
    final events = user?.role == UserRole.admin
        ? r.watch(adminEventsProvider)
        : r.watch(eventsProvider);
    return Scaffold(
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('Community events',
                          style: Theme.of(c).textTheme.headlineSmall),
                      const SizedBox(height: AppSpacing.xs),
                      const Text('Find your next opportunity to connect.'),
                    ],
                  ),
                ),
                if (user?.role == UserRole.admin ||
                    user?.role == UserRole.alumni)
                  IconButton.filledTonal(
                    tooltip: 'Create event',
                    onPressed: () => _eventEditor(c, r, null),
                    icon: const Icon(Icons.add),
                  ),
              ],
            ),
          ),
          Expanded(
            child: _list<EventItem>(
              events,
              (event) => AppCard(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (event.imageUrl?.isNotEmpty == true)
                      ClipRRect(
                        borderRadius: BorderRadius.circular(AppRadius.md),
                        child: Image.network(
                          event.imageUrl!,
                          height: 150,
                          width: double.infinity,
                          fit: BoxFit.cover,
                          errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                        ),
                      ),
                    ListTile(
                      contentPadding:
                          const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
                      leading: Icon(
                        Icons.event_outlined,
                        color: Theme.of(c).colorScheme.primary,
                      ),
                      title: Text(event.title,
                          style: const TextStyle(fontWeight: FontWeight.w700)),
                      subtitle: Padding(
                        padding: const EdgeInsets.only(top: AppSpacing.xs),
                        child: Text(
                          [
                            if (event.eventDate?.isNotEmpty == true)
                              event.eventDate!,
                            if (event.location?.isNotEmpty == true)
                              event.location!,
                            '${event.attendeeCount} attending',
                          ].join(' · '),
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => c.push('/event/${event.id}'),
                    ),
                    if (event.category?.isNotEmpty == true ||
                        event.status?.isNotEmpty == true)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(
                            AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.md),
                        child: Wrap(
                          spacing: AppSpacing.sm,
                          children: [
                            if (event.category?.isNotEmpty == true)
                              Chip(label: Text(event.category!)),
                            if (event.status?.isNotEmpty == true)
                              StatusBadge(event.status!),
                          ],
                        ),
                      ),
                    if (user?.role == UserRole.admin)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(
                            AppSpacing.sm, 0, AppSpacing.sm, AppSpacing.sm),
                        child: Wrap(
                          alignment: WrapAlignment.end,
                          children: [
                            if (_workingEventIds.contains(event.id))
                              const Padding(
                                padding: EdgeInsets.all(AppSpacing.md),
                                child: SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                ),
                              ),
                            IconButton(
                              tooltip: 'Approve',
                              onPressed: _workingEventIds.contains(event.id) ||
                                      event.status?.toUpperCase() == 'APPROVED'
                                  ? null
                                  : () => _runAdminAction(
                                        c,
                                        event.id,
                                        'Event approved',
                                        () => r
                                            .read(appRepositoryProvider)
                                            .approveEvent(event.id),
                                      ),
                              icon: const Icon(Icons.check_circle_outline),
                            ),
                            IconButton(
                              tooltip: 'Reject',
                              onPressed: _workingEventIds.contains(event.id)
                                  ? null
                                  : () => _runAdminAction(
                                        c,
                                        event.id,
                                        'Event rejected',
                                        () => r
                                            .read(appRepositoryProvider)
                                            .rejectEvent(event.id),
                                      ),
                              icon: const Icon(Icons.cancel_outlined),
                            ),
                            IconButton(
                              tooltip: 'Edit',
                              onPressed: _workingEventIds.contains(event.id)
                                  ? null
                                  : () => _eventEditor(c, r, event),
                              icon: const Icon(Icons.edit_outlined),
                            ),
                            IconButton(
                              tooltip: 'Delete',
                              onPressed: _workingEventIds.contains(event.id)
                                  ? null
                                  : () => _deleteEvent(c, r, event.id),
                              icon: const Icon(Icons.delete_outline),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
              onRetry: () => r.invalidate(
                user?.role == UserRole.admin
                    ? adminEventsProvider
                    : eventsProvider,
              ),
              onLoadMore: () {
                if (user?.role == UserRole.admin) {
                  r.read(adminEventsProvider.notifier).loadNextPage();
                } else {
                  r.read(eventsProvider.notifier).loadNextPage();
                }
              },
            ),
          ),
        ],
      ),
    );
  }
}

Future<void> _eventAction(
  BuildContext c,
  WidgetRef r,
  Future<void> Function() action,
  VoidCallback refresh,
) async {
  try {
    await action();
    refresh();
  } catch (error) {
    if (c.mounted) {
      ScaffoldMessenger.of(c).showSnackBar(
        SnackBar(content: Text(userFacingError(error))),
      );
    }
  }
}

Future<void> _deleteEvent(BuildContext c, WidgetRef r, int id) async {
  final confirmed = await showDialog<bool>(
    context: c,
    builder: (context) => AlertDialog(
      title: const Text('Delete event?'),
      content: const Text('This action cannot be undone.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, false),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(context, true),
          child: const Text('Delete'),
        ),
      ],
    ),
  );

  if (confirmed != true) return;
  if (!c.mounted) return;

  await _eventAction(
    c,
    r,
    () => r.read(appRepositoryProvider).deleteEvent(id),
    () => r.invalidate(
      r.read(authProvider).valueOrNull?.role == UserRole.admin
          ? adminEventsProvider
          : eventsProvider,
    ),
  );

  if (!c.mounted) return;
}

Future<void> _eventEditor(
    BuildContext context, WidgetRef ref, EventItem? existing) async {
  final title = TextEditingController(text: existing?.title);
  final description = TextEditingController(text: existing?.description);
  final date = TextEditingController(text: existing?.eventDate);
  final location = TextEditingController(text: existing?.location);
  final category = TextEditingController(text: existing?.category);
  final link = TextEditingController(text: existing?.meetingLink);
  final formKey = GlobalKey<FormState>();
  var saving = false;
  final result = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => StatefulBuilder(
      builder: (dialogContext, setDialogState) => AlertDialog(
        title: Text(existing == null ? 'Create event' : 'Edit event'),
        content: SingleChildScrollView(
          child: Form(
            key: formKey,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextFormField(
                  controller: title,
                  decoration: const InputDecoration(labelText: 'Title'),
                  validator: (value) =>
                      value == null || value.trim().isEmpty ? 'Required' : null,
                ),
                TextFormField(
                  controller: description,
                  decoration: const InputDecoration(labelText: 'Description'),
                ),
                TextFormField(
                  controller: date,
                  decoration: const InputDecoration(labelText: 'Date and time'),
                ),
                TextFormField(
                  controller: location,
                  decoration: const InputDecoration(labelText: 'Location'),
                ),
                TextFormField(
                  controller: category,
                  decoration: const InputDecoration(labelText: 'Category'),
                ),
                TextFormField(
                  controller: link,
                  decoration: const InputDecoration(labelText: 'Meeting link'),
                ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(
            onPressed:
                saving ? null : () => Navigator.pop(dialogContext, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: saving
                ? null
                : () async {
                    if (!(formKey.currentState?.validate() ?? false)) return;
                    final event = EventItem(
                      id: existing?.id ?? 0,
                      title: title.text.trim(),
                      description: description.text.trim(),
                      eventDate: date.text.trim(),
                      location: location.text.trim(),
                      category: category.text.trim(),
                      meetingLink: link.text.trim(),
                    );
                    setDialogState(() => saving = true);
                    try {
                      if (existing == null) {
                        await ref
                            .read(appRepositoryProvider)
                            .createEvent(event);
                      } else {
                        await ref
                            .read(appRepositoryProvider)
                            .updateEvent(event);
                      }
                      if (dialogContext.mounted) {
                        Navigator.pop(dialogContext, true);
                      }
                    } catch (error) {
                      if (dialogContext.mounted) {
                        setDialogState(() => saving = false);
                        ScaffoldMessenger.of(dialogContext).showSnackBar(
                          SnackBar(
                            content: Text(userFacingError(error)),
                          ),
                        );
                      }
                    }
                  },
            child: saving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(existing == null ? 'Create' : 'Save'),
          ),
        ],
      ),
    ),
  );
  for (final controller in [
    title,
    description,
    date,
    location,
    category,
    link
  ]) {
    controller.dispose();
  }
  if (result == true) {
    ref.invalidate(eventsProvider);
    ref.invalidate(adminEventsProvider);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            existing == null ? 'Event created' : 'Event updated',
          ),
        ),
      );
    }
  }
}

class ProfileScreen extends ConsumerStatefulWidget {
  const ProfileScreen({super.key});
  @override
  ConsumerState<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends ConsumerState<ProfileScreen> {
  final _name = TextEditingController();
  final _bio = TextEditingController();
  final _location = TextEditingController();
  final _college = TextEditingController();
  final _branch = TextEditingController();
  final _passoutYear = TextEditingController();
  final _rollno = TextEditingController();
  final _section = TextEditingController();
  final _skills = TextEditingController();
  final _company = TextEditingController();
  final _jobRole = TextEditingController();
  final _linkedin = TextEditingController();
  final _github = TextEditingController();
  final _interests = TextEditingController();
  bool _loaded = false;
  bool _saving = false;

  @override
  void dispose() {
    _name.dispose();
    _bio.dispose();
    _location.dispose();
    for (final controller in [
      _college,
      _branch,
      _passoutYear,
      _rollno,
      _section,
      _skills,
      _company,
      _jobRole,
      _linkedin,
      _github,
      _interests
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  void _populateFields(User user) {
    _name.text = user.name;
    _bio.text = user.bio ?? '';
    _location.text = user.location ?? '';
    _college.text = user.college ?? '';
    _branch.text = user.branch ?? '';
    _passoutYear.text = user.passoutYear ?? '';
    _rollno.text = user.rollno ?? '';
    _section.text = user.section ?? '';
    _skills.text = user.skills ?? '';
    _company.text = user.company ?? '';
    _jobRole.text = user.jobRole ?? '';
    _linkedin.text = user.linkedin ?? '';
    _github.text = user.github ?? '';
    _interests.text = user.interests ?? '';
  }

  bool _hasChanges(User user) =>
      _name.text.trim() != user.name.trim() ||
      _bio.text.trim() != (user.bio ?? '').trim() ||
      _location.text.trim() != (user.location ?? '').trim() ||
      _college.text.trim() != (user.college ?? '').trim() ||
      _branch.text.trim() != (user.branch ?? '').trim() ||
      _passoutYear.text.trim() != (user.passoutYear ?? '').trim() ||
      _rollno.text.trim() != (user.rollno ?? '').trim() ||
      _section.text.trim() != (user.section ?? '').trim() ||
      _skills.text.trim() != (user.skills ?? '').trim() ||
      _company.text.trim() != (user.company ?? '').trim() ||
      _jobRole.text.trim() != (user.jobRole ?? '').trim() ||
      _linkedin.text.trim() != (user.linkedin ?? '').trim() ||
      _github.text.trim() != (user.github ?? '').trim() ||
      _interests.text.trim() != (user.interests ?? '').trim();

  @override
  Widget build(BuildContext context) {
    final u = ref.watch(authProvider).valueOrNull;
    final themeMode = ref.watch(themeModeProvider);
    if (u == null) {
      return const EmptyState(
        title: 'Profile unavailable',
        message: 'Sign in again to view and edit your profile.',
        icon: Icons.person_outline,
      );
    }
    if (!_loaded) {
      _loaded = true;
      _populateFields(u);
    }
    final hasChanges = _hasChanges(u);
    final compactAppearance = MediaQuery.sizeOf(context).width < 440;
    final themeDropdown = SizedBox(
      width: compactAppearance ? double.infinity : 155,
      child: DropdownButtonFormField<ThemeMode>(
        initialValue: themeMode,
        decoration: const InputDecoration(
          labelText: 'Theme',
          contentPadding: EdgeInsets.symmetric(
            horizontal: AppSpacing.md,
            vertical: AppSpacing.xs,
          ),
        ),
        items: const [
          DropdownMenuItem(value: ThemeMode.system, child: Text('System')),
          DropdownMenuItem(value: ThemeMode.light, child: Text('Light')),
          DropdownMenuItem(value: ThemeMode.dark, child: Text('Dark')),
        ],
        onChanged: (value) async {
          if (value == null) return;
          try {
            await ref.read(themeModeProvider.notifier).setThemeMode(value);
          } catch (error) {
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(
                    'Theme changed, but could not save preference: ${userFacingError(error)}',
                  ),
                ),
              );
            }
          }
        },
      ),
    );
    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 780),
        child: ListView(
          padding: const EdgeInsets.all(AppSpacing.lg),
          children: [
            AppCard(
              child: Row(
                children: [
                  UserAvatar(
                      name: u.name, imageUrl: u.profileImage, radius: 34),
                  const SizedBox(width: AppSpacing.lg),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          u.name,
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        const SizedBox(height: AppSpacing.xs),
                        Text(u.email),
                        const SizedBox(height: AppSpacing.sm),
                        StatusBadge(u.role.name),
                        if (u.status.isNotEmpty) ...[
                          const SizedBox(height: AppSpacing.xs),
                          StatusBadge(u.status),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            AppCard(
              child: compactAppearance
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Row(
                          children: [
                            Icon(Icons.palette_outlined),
                            SizedBox(width: AppSpacing.md),
                            Text('Appearance'),
                          ],
                        ),
                        const SizedBox(height: AppSpacing.sm),
                        themeDropdown,
                      ],
                    )
                  : Row(
                      children: [
                        const Icon(Icons.palette_outlined),
                        const SizedBox(width: AppSpacing.md),
                        const Expanded(child: Text('Appearance')),
                        themeDropdown,
                      ],
                    ),
            ),
            const SizedBox(height: AppSpacing.xl),
            const AppSectionTitle('Edit your profile'),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  _profileField(
                    _name,
                    'Name',
                    icon: Icons.person_outline,
                  ),
                  _profileField(
                    _bio,
                    'About you',
                    icon: Icons.notes_outlined,
                    maxLines: 3,
                  ),
                  _profileField(
                    _location,
                    'Location',
                    icon: Icons.location_on_outlined,
                  ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Education'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_college, 'College'),
                  _profileField(_branch, 'Branch'),
                  _profileField(
                    _passoutYear,
                    'Passout year',
                    keyboardType: TextInputType.number,
                  ),
                  _profileField(_rollno, 'Roll number'),
                  _profileField(_section, 'Section'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Experience'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_jobRole, 'Job role'),
                  _profileField(_company, 'Company'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Skills and interests'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_skills, 'Skills'),
                  _profileField(_interests, 'Interests'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.md),
            AppCard(
              child: Column(
                children: [
                  const AppSectionTitle('Links'),
                  const SizedBox(height: AppSpacing.sm),
                  _profileField(_linkedin, 'LinkedIn'),
                  _profileField(_github, 'GitHub'),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            FilledButton(
              onPressed: _saving || !hasChanges
                  ? null
                  : () async {
                      if (_name.text.trim().isEmpty) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('Name cannot be empty.'),
                          ),
                        );
                        return;
                      }
                      if (_passoutYear.text.trim().isNotEmpty &&
                          !RegExp(r'^\d{4}$')
                              .hasMatch(_passoutYear.text.trim())) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content:
                                Text('Enter a valid 4-digit passout year.'),
                          ),
                        );
                        return;
                      }
                      setState(() => _saving = true);
                      final updated = User(
                        id: u.id,
                        name: _name.text.trim(),
                        email: u.email,
                        role: u.role,
                        status: u.status,
                        bio: _bio.text.trim(),
                        location: _location.text.trim(),
                        college: _college.text.trim(),
                        branch: _branch.text.trim(),
                        passoutYear: _passoutYear.text.trim(),
                        rollno: _rollno.text.trim(),
                        section: _section.text.trim(),
                        skills: _skills.text.trim(),
                        company: _company.text.trim(),
                        jobRole: _jobRole.text.trim(),
                        linkedin: _linkedin.text.trim(),
                        profileImage: u.profileImage,
                        interests: _interests.text.trim(),
                        github: _github.text.trim(),
                      );
                      try {
                        final saved = await ref
                            .read(appRepositoryProvider)
                            .updateUser(updated);
                        ref.read(authProvider.notifier).setUser(saved);
                        if (!context.mounted) return;
                        setState(() => _saving = false);
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(content: Text('Profile saved')),
                        );
                      } catch (error) {
                        if (!context.mounted) return;
                        setState(() => _saving = false);
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(content: Text(userFacingError(error))),
                        );
                      }
                    },
              child: Text(_saving ? 'Saving…' : 'Save profile'),
            ),
            if (hasChanges) ...[
              const SizedBox(height: AppSpacing.sm),
              OutlinedButton(
                onPressed:
                    _saving ? null : () => setState(() => _populateFields(u)),
                child: const Text('Discard changes'),
              ),
            ],
            const SizedBox(height: AppSpacing.sm),
            OutlinedButton(
              onPressed: () => ref.read(authProvider.notifier).signOut(),
              child: const Text('Sign out'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _profileField(
    TextEditingController controller,
    String label, {
    IconData? icon,
    int maxLines = 1,
    TextInputType? keyboardType,
  }) =>
      Padding(
        padding: const EdgeInsets.only(bottom: AppSpacing.md),
        child: TextField(
          controller: controller,
          onChanged: (_) => setState(() {}),
          maxLines: maxLines,
          keyboardType: keyboardType,
          decoration: InputDecoration(
            labelText: label,
            prefixIcon: icon == null ? null : Icon(icon),
          ),
        ),
      );
}

class ConnectionsScreen extends ConsumerStatefulWidget {
  const ConnectionsScreen({super.key});
  @override
  ConsumerState<ConnectionsScreen> createState() => _ConnectionsScreenState();
}

class _ConnectionsScreenState extends ConsumerState<ConnectionsScreen> {
  String _filter = 'Received';
  final _workingIds = <int>{};

  Future<void> _respond(ConnectionItem connection, String status) async {
    if (!_workingIds.add(connection.id)) return;
    setState(() {});
    try {
      await ref
          .read(appRepositoryProvider)
          .respondConnection(connection.id, status);
      final refreshedConnections = await ref.refresh(connectionsProvider.future);
      if (!mounted) return;
      if (refreshedConnections.isEmpty) {
        return;
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    } finally {
      if (mounted) setState(() => _workingIds.remove(connection.id));
    }
  }

  @override
  Widget build(BuildContext c) {
    final me = ref.watch(authProvider).valueOrNull?.email ?? '';
    final connections = ref.watch(connectionsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Connections')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'Received', label: Text('Received')),
                  ButtonSegment(value: 'Sent', label: Text('Sent')),
                  ButtonSegment(value: 'Connected', label: Text('Connected')),
                  ButtonSegment(value: 'All', label: Text('All')),
                ],
                selected: {_filter},
                onSelectionChanged: (selection) =>
                    setState(() => _filter = selection.first),
              ),
            ),
          ),
          Expanded(
            child: connections.when(
              loading: () => const LoadingState(),
              error: (error, _) => ErrorState(
                message: userFacingError(error),
                onRetry: () => ref.invalidate(connectionsProvider),
              ),
              data: (items) {
                final visible = items.where((connection) {
                  final incoming = connection.receiver?.email == me;
                  final outgoing = connection.requester?.email == me;
                  final accepted =
                      connection.status.toUpperCase() == 'ACCEPTED';
                  return switch (_filter) {
                    'Received' =>
                      incoming && connection.status.toUpperCase() == 'PENDING',
                    'Sent' =>
                      outgoing && connection.status.toUpperCase() == 'PENDING',
                    'Connected' => accepted,
                    _ => true,
                  };
                }).toList();

                if (visible.isEmpty) {
                  return EmptyState(
                    title: switch (_filter) {
                      'Received' => 'No incoming requests',
                      'Sent' => 'No pending requests',
                      'Connected' => 'No connections yet',
                      _ => 'No connection activity yet',
                    },
                    message: 'Your network activity will appear here.',
                    icon: Icons.people_outline,
                  );
                }
                return ListView.builder(
                  padding: const EdgeInsets.fromLTRB(
                      AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.lg),
                  itemCount: visible.length,
                  itemBuilder: (context, index) {
                    final connection = visible[index];
                    final incoming = connection.receiver?.email == me;
                    final other = incoming
                        ? connection.requester
                        : connection.receiver ?? connection.requester;
                    final pending =
                        connection.status.toUpperCase() == 'PENDING';
                    return Padding(
                      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                      child: AppCard(
                        child: ListTile(
                          contentPadding: const EdgeInsets.all(AppSpacing.sm),
                          leading: UserAvatar(
                            name: other?.name,
                            imageUrl: other?.profileImage,
                          ),
                          title: Text(other?.name ?? 'Connection'),
                          subtitle: Text([
                            if (other?.jobRole?.isNotEmpty == true)
                              other!.jobRole!,
                            if (other?.company?.isNotEmpty == true)
                              other!.company!,
                            connection.status,
                          ].join(' · ')),
                          onTap: other == null
                              ? null
                              : () => c.push('/user/${other.id}'),
                          trailing: pending && incoming
                              ? Wrap(
                                  children: [
                                    IconButton(
                                      tooltip: 'Accept request',
                                      onPressed: _workingIds
                                              .contains(connection.id)
                                          ? null
                                          : () =>
                                              _respond(connection, 'ACCEPTED'),
                                      icon: const Icon(
                                          Icons.check_circle_outline),
                                    ),
                                    IconButton(
                                      tooltip: 'Reject request',
                                      onPressed: _workingIds
                                              .contains(connection.id)
                                          ? null
                                          : () =>
                                              _respond(connection, 'REJECTED'),
                                      icon: const Icon(Icons.close),
                                    ),
                                  ],
                                )
                              : StatusBadge(connection.status),
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class NotificationsScreen extends ConsumerWidget {
  const NotificationsScreen({super.key});
  @override
  Widget build(BuildContext c, WidgetRef r) {
    final notifications = r.watch(notificationsProvider);
    final unreadCount = r.watch(unreadNotificationCountProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Notifications')),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg, AppSpacing.sm, AppSpacing.lg, AppSpacing.md),
            child: Row(
              children: [
                const Icon(Icons.notifications_active_outlined),
                const SizedBox(width: AppSpacing.sm),
                unreadCount.when(
                  data: (count) => Text(
                    count == 1
                        ? '1 unread notification'
                        : '$count unread notifications',
                    style: Theme.of(c).textTheme.titleMedium,
                  ),
                  loading: () => const Text('Checking unread activity…'),
                  error: (error, _) => Expanded(
                    child: Text(
                      userFacingError(error),
                      style: TextStyle(color: Theme.of(c).colorScheme.error),
                    ),
                  ),
                ),
                if (unreadCount.hasError)
                  IconButton(
                    tooltip: 'Retry unread count',
                    onPressed: () =>
                        r.invalidate(unreadNotificationCountProvider),
                    icon: const Icon(Icons.refresh),
                  ),
              ],
            ),
          ),
          Expanded(
            child: _list<NotificationItem>(
              notifications,
              (notification) => AppCard(
                child: ListTile(
                  contentPadding: const EdgeInsets.symmetric(
                    horizontal: AppSpacing.md,
                    vertical: AppSpacing.xs,
                  ),
                  leading: CircleAvatar(
                    backgroundColor: notification.isRead
                        ? Theme.of(c).colorScheme.surfaceContainerHighest
                        : Theme.of(c).colorScheme.primaryContainer,
                    child: Icon(
                      _notificationIcon(notification.type),
                      color: notification.isRead
                          ? Theme.of(c).colorScheme.onSurfaceVariant
                          : Theme.of(c).colorScheme.primary,
                    ),
                  ),
                  title: Text(
                    notification.message,
                    style: TextStyle(
                      fontWeight: notification.isRead
                          ? FontWeight.normal
                          : FontWeight.w700,
                    ),
                  ),
                  subtitle: notification.timestamp == null
                      ? null
                      : Text(_formatTimestamp(notification.timestamp!)),
                  trailing: notification.isRead
                      ? const Icon(Icons.done, size: 18)
                      : Icon(
                          Icons.circle,
                          size: 10,
                          color: Theme.of(c).colorScheme.primary,
                        ),
                  onTap: notification.isRead
                      ? null
                      : () async {
                          try {
                            await r
                                .read(appRepositoryProvider)
                                .markRead(notification.id);
                            r.invalidate(notificationsProvider);
                            r.invalidate(unreadNotificationCountProvider);
                          } catch (error) {
                            if (c.mounted) {
                              ScaffoldMessenger.of(c).showSnackBar(
                                SnackBar(
                                  content: Text(userFacingError(error)),
                                ),
                              );
                            }
                          }
                        },
                ),
              ),
              onRetry: () {
                r.invalidate(notificationsProvider);
                r.invalidate(unreadNotificationCountProvider);
              },
              emptyTitle: 'No notifications yet',
              emptyMessage: 'New community updates will appear here.',
              emptyIcon: Icons.notifications_none,
            ),
          ),
        ],
      ),
    );
  }
}

IconData _notificationIcon(String? type) => switch (type?.toUpperCase()) {
      'MESSAGE' => Icons.chat_bubble_outline,
      'EVENT' => Icons.event_outlined,
      'CONNECTION' => Icons.people_outline,
      _ => Icons.notifications_outlined,
    };

String _formatTimestamp(String value) {
  final parsed = DateTime.tryParse(value);
  if (parsed == null) return value;
  return DateFormat.yMMMd().add_jm().format(parsed.toLocal());
}

class UserScreen extends ConsumerStatefulWidget {
  const UserScreen({super.key, required this.id});
  final int id;

  @override
  ConsumerState<UserScreen> createState() => _UserScreenState();
}

class _UserScreenState extends ConsumerState<UserScreen> {
  bool _connectionActionInProgress = false;

  Future<void> _performConnectionAction(
    BuildContext context,
    Future<void> Function() action,
    String successMessage,
  ) async {
    if (_connectionActionInProgress) return;
    setState(() => _connectionActionInProgress = true);
    try {
      await action();
      if (!mounted) return;
      final refreshedConnections = await ref.refresh(connectionsProvider.future);
      if (!context.mounted) return;
      if (refreshedConnections.isNotEmpty || successMessage.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(successMessage)),
        );
      }
    } catch (error) {
      if (mounted && context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _connectionActionInProgress = false);
      }
    }
  }

  @override
  Widget build(BuildContext c) {
    final r = ref;
    final id = widget.id;
    final userState = r.watch(userByIdProvider(id));
    final connectionState = r.watch(connectionsProvider);
    final currentUser = r.watch(authProvider).valueOrNull;
    final currentEmail = currentUser?.email ?? '';
    final isOwnProfile = currentUser?.id == id;
    final connections = connectionState.valueOrNull ?? const <ConnectionItem>[];
    final matches = connections
        .where((x) => x.requester?.id == id || x.receiver?.id == id)
        .toList();
    final connection = matches.isEmpty ? null : matches.first;
    final status = connection?.status.toUpperCase();
    return Scaffold(
      appBar: AppBar(
        actions: [
          if (!isOwnProfile && connectionState.isLoading)
            const Padding(
              padding: EdgeInsets.all(AppSpacing.lg),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            )
          else if (!isOwnProfile && connectionState.hasError)
            IconButton(
              tooltip: 'Retry connection status',
              onPressed: () => r.invalidate(connectionsProvider),
              icon: const Icon(Icons.refresh),
            )
          else if (!isOwnProfile && status == 'ACCEPTED')
            TextButton.icon(
              onPressed: () {
                final email = userState.valueOrNull?.email;
                if (email != null && email.isNotEmpty) {
                  c.push('/chat/${Uri.encodeComponent(email)}');
                }
              },
              icon: const Icon(Icons.chat_bubble_outline),
              label: const Text('Message'),
            )
          else if (!isOwnProfile &&
              status == 'PENDING' &&
              connection?.requester?.email == currentEmail)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: AppSpacing.lg),
              child: Center(child: StatusBadge('REQUEST SENT')),
            )
          else if (!isOwnProfile && status == 'PENDING' && connection != null)
            IconButton(
              tooltip: 'Accept connection request',
              onPressed: _connectionActionInProgress
                  ? null
                  : () => _performConnectionAction(
                        c,
                        () async {
                          await r
                              .read(appRepositoryProvider)
                              .respondConnection(connection.id, 'ACCEPTED');
                        },
                        'Connection accepted',
                      ),
              icon: _connectionActionInProgress
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.person_add_alt_1),
            )
          else if (!isOwnProfile && status != 'ACCEPTED')
            IconButton(
              tooltip: 'Connect',
              onPressed: _connectionActionInProgress
                  ? null
                  : () => _performConnectionAction(
                        c,
                        () async {
                          await r
                              .read(appRepositoryProvider)
                              .requestConnection(id);
                        },
                        'Connection requested',
                      ),
              icon: _connectionActionInProgress
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.person_add_alt_1),
            ),
          const SizedBox(width: AppSpacing.sm),
        ],
      ),
      body: userState.when(
        loading: () => const LoadingState(),
        error: (error, _) => ErrorState(
          message: userFacingError(error),
          onRetry: () => r.invalidate(userByIdProvider(id)),
        ),
        data: (user) {
          final education = [
            if (user.college?.isNotEmpty == true) user.college!,
            if (user.branch?.isNotEmpty == true) user.branch!,
            if (user.passoutYear?.isNotEmpty == true)
              'Class of ${user.passoutYear}',
            if (user.rollno?.isNotEmpty == true) 'Roll ${user.rollno}',
            if (user.section?.isNotEmpty == true) 'Section ${user.section}',
          ];
          final experience = [
            if (user.jobRole?.isNotEmpty == true) user.jobRole!,
            if (user.company?.isNotEmpty == true) user.company!,
          ];
          return ListView(
            padding: const EdgeInsets.all(AppSpacing.xl),
            children: [
              AppCard(
                child: Column(
                  children: [
                    UserAvatar(
                      name: user.name,
                      imageUrl: user.profileImage,
                      radius: 42,
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Text(user.name,
                        style: Theme.of(c).textTheme.headlineSmall,
                        textAlign: TextAlign.center),
                    const SizedBox(height: AppSpacing.xs),
                    Text(user.email, textAlign: TextAlign.center),
                    const SizedBox(height: AppSpacing.md),
                    StatusBadge(user.role.name),
                    if (user.location?.isNotEmpty == true) ...[
                      const SizedBox(height: AppSpacing.sm),
                      Text(user.location!),
                    ],
                  ],
                ),
              ),
              if (user.bio?.isNotEmpty == true) ...[
                const SizedBox(height: AppSpacing.lg),
                AppCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const AppSectionTitle('About'),
                      const SizedBox(height: AppSpacing.sm),
                      Text(user.bio!),
                    ],
                  ),
                ),
              ],
              if (experience.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.lg),
                AppCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const AppSectionTitle('Experience'),
                      const SizedBox(height: AppSpacing.sm),
                      Text(experience.join(' · ')),
                    ],
                  ),
                ),
              ],
              if (education.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.lg),
                AppCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const AppSectionTitle('Education'),
                      const SizedBox(height: AppSpacing.sm),
                      Text(education.join(' · ')),
                    ],
                  ),
                ),
              ],
              for (final section in [
                ('Skills', user.skills, Icons.auto_awesome_outlined),
                ('Interests', user.interests, Icons.favorite_border),
              ])
                if (section.$2?.isNotEmpty == true) ...[
                  const SizedBox(height: AppSpacing.lg),
                  AppCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        AppSectionTitle(section.$1),
                        const SizedBox(height: AppSpacing.sm),
                        Wrap(
                          spacing: AppSpacing.sm,
                          runSpacing: AppSpacing.xs,
                          children: section.$2!
                              .split(RegExp(r'[,;]'))
                              .map((value) => value.trim())
                              .where((value) => value.isNotEmpty)
                              .map((value) => Chip(
                                    avatar: Icon(section.$3, size: 16),
                                    label: Text(value),
                                  ))
                              .toList(),
                        ),
                      ],
                    ),
                  ),
                ],
              if (user.linkedin?.isNotEmpty == true ||
                  user.github?.isNotEmpty == true) ...[
                const SizedBox(height: AppSpacing.lg),
                AppCard(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const AppSectionTitle('Links'),
                      if (user.linkedin?.isNotEmpty == true)
                        ListTile(
                          leading: const Icon(Icons.link),
                          title: const Text('LinkedIn'),
                          subtitle: Text(user.linkedin!),
                        ),
                      if (user.github?.isNotEmpty == true)
                        ListTile(
                          leading: const Icon(Icons.code),
                          title: const Text('GitHub'),
                          subtitle: Text(user.github!),
                        ),
                    ],
                  ),
                ),
              ],
              if (status == 'ACCEPTED') ...[
                const SizedBox(height: AppSpacing.lg),
                FilledButton.icon(
                  onPressed: () =>
                      c.push('/chat/${Uri.encodeComponent(user.email)}'),
                  icon: const Icon(Icons.chat_bubble_outline),
                  label: const Text('Message'),
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}

class EventScreen extends StatelessWidget {
  const EventScreen({super.key, required this.id});
  final int id;
  @override
  Widget build(BuildContext c) => _EventDetails(id: id);
}

class _EventDetails extends ConsumerStatefulWidget {
  const _EventDetails({required this.id});
  final int id;
  @override
  ConsumerState<_EventDetails> createState() => _EventDetailsState();
}

class _EventDetailsState extends ConsumerState<_EventDetails> {
  bool _registering = false;
  bool _registeredDuringSession = false;
  @override
  Widget build(BuildContext c) {
    final id = widget.id;
    final isAdmin = ref.watch(authProvider).valueOrNull?.role == UserRole.admin;
    final events = ref.watch(isAdmin ? adminEventsProvider : eventsProvider);
    final eventsProviderToRefresh =
        isAdmin ? adminEventsProvider : eventsProvider;
    return Scaffold(
      appBar: AppBar(title: const Text('Event details')),
      body: events.when(
        loading: () => const LoadingState(),
        error: (error, _) => ErrorState(
          message: userFacingError(error),
          onRetry: () => ref.invalidate(eventsProviderToRefresh),
        ),
        data: (allEvents) {
          final matches = allEvents.where((event) => event.id == id);
          if (matches.isEmpty) {
            return const EmptyState(
              title: 'Event not found',
              message: 'This event may no longer be available.',
              icon: Icons.event_busy_outlined,
            );
          }
          final event = matches.first;
          return ListView(
            padding: const EdgeInsets.all(AppSpacing.xl),
            children: [
              if (event.imageUrl?.isNotEmpty == true)
                ClipRRect(
                  borderRadius: BorderRadius.circular(AppRadius.lg),
                  child: Image.network(
                    event.imageUrl!,
                    height: 220,
                    fit: BoxFit.cover,
                    errorBuilder: (_, __, ___) => const SizedBox.shrink(),
                  ),
                ),
              const SizedBox(height: AppSpacing.lg),
              Text(event.title, style: Theme.of(c).textTheme.headlineSmall),
              const SizedBox(height: AppSpacing.sm),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  if (event.category?.isNotEmpty == true)
                    Chip(
                      avatar: const Icon(Icons.sell_outlined, size: 16),
                      label: Text(event.category!),
                    ),
                  if (event.status?.isNotEmpty == true)
                    StatusBadge(event.status!),
                ],
              ),
              const SizedBox(height: AppSpacing.md),
              AppCard(
                child: Column(
                  children: [
                    if (event.eventDate?.isNotEmpty == true)
                      ListTile(
                        leading: const Icon(Icons.calendar_month_outlined),
                        title: Text(event.eventDate!),
                      ),
                    if (event.location?.isNotEmpty == true)
                      ListTile(
                        leading: const Icon(Icons.location_on_outlined),
                        title: Text(event.location!),
                      ),
                    ListTile(
                      leading: const Icon(Icons.groups_outlined),
                      title: Text(
                        '${event.attendeeCount} ${event.attendeeCount == 1 ? 'attendee' : 'attendees'}',
                      ),
                    ),
                    if (event.meetingLink?.isNotEmpty == true)
                      ListTile(
                        leading: const Icon(Icons.link),
                        title: const Text('Meeting link'),
                        subtitle: SelectableText(event.meetingLink!),
                      ),
                  ],
                ),
              ),
              if (event.description?.isNotEmpty == true) ...[
                const SizedBox(height: AppSpacing.lg),
                const AppSectionTitle('About this event'),
                const SizedBox(height: AppSpacing.sm),
                Text(event.description!),
              ],
              const SizedBox(height: AppSpacing.xl),
              SizedBox(
                width: double.infinity,
                child: _registeredDuringSession
                    ? OutlinedButton.icon(
                        onPressed: _registering
                            ? null
                            : () => _updateRegistration(
                                  id,
                                  register: false,
                                  refreshProvider: () =>
                                      ref.invalidate(eventsProviderToRefresh),
                                ),
                        icon: _registering
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.event_busy_outlined),
                        label: Text(
                          _registering ? 'Please wait…' : 'Cancel registration',
                        ),
                      )
                    : FilledButton.icon(
                        onPressed: _registering
                            ? null
                            : () => _updateRegistration(
                                  id,
                                  register: true,
                                  refreshProvider: () =>
                                      ref.invalidate(eventsProviderToRefresh),
                                ),
                        icon: _registering
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.event_available_outlined),
                        label: Text(_registering ? 'Please wait…' : 'Register'),
                      ),
              ),
              if (isAdmin) ...[
                const SizedBox(height: AppSpacing.md),
                OutlinedButton.icon(
                  onPressed: () => _showAttendees(id),
                  icon: const Icon(Icons.groups_outlined),
                  label: const Text('View attendees'),
                ),
              ],
            ],
          );
        },
      ),
    );
  }

  Future<void> _updateRegistration(
    int id, {
    required bool register,
    required VoidCallback refreshProvider,
  }) async {
    setState(() => _registering = true);
    try {
      final repository = ref.read(appRepositoryProvider);
      if (register) {
        await repository.registerEvent(id);
      } else {
        await repository.cancelRegistration(id);
      }
      if (!mounted) return;
      setState(() => _registeredDuringSession = register);
      refreshProvider();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
                register ? 'Registered for event' : 'Registration cancelled'),
          ),
        );
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    } finally {
      if (mounted) setState(() => _registering = false);
    }
  }

  Future<void> _showAttendees(int id) async {
    try {
      final attendees = await ref.read(appRepositoryProvider).attendees(id);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Attendees'),
          content: SizedBox(
            width: 360,
            child: attendees.isEmpty
                ? const EmptyState(
                    title: 'No attendees yet',
                    icon: Icons.groups_outlined,
                  )
                : ListView(
                    shrinkWrap: true,
                    children: [
                      for (final attendee in attendees)
                        ListTile(
                          leading: UserAvatar(name: attendee.name),
                          title: Text(
                              attendee.name ?? attendee.email ?? 'Attendee'),
                          subtitle: attendee.registeredAt == null
                              ? null
                              : Text(_formatTimestamp(attendee.registeredAt!)),
                        ),
                    ],
                  ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Close'),
            ),
          ],
        ),
      );
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    }
  }
}

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key, required this.email});
  final String email;
  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final _input = TextEditingController();
  final _scrollController = ScrollController();
  final _messages = <ChatMessage>[];
  StreamSubscription<ChatMessage>? _sub;
  StreamSubscription<RealtimeState>? _realtimeSub;
  RealtimeState _realtimeState = RealtimeState.disconnected;
  bool _loading = true;
  bool _loadingOlder = false;
  bool _hasMoreHistory = true;
  int _nextHistoryPage = 1;
  static const _historyPageSize = 50;
  String? _error;
  String? _sendError;

  bool get _isInbox => widget.email == 'inbox';

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_loadOlderWhenAtTop);
    final realtime = ref.read(realtimeServiceProvider);
    _realtimeState = realtime.current;
    _realtimeSub = realtime.states.listen((state) {
      if (mounted) setState(() => _realtimeState = state);
    });
    if (!_isInbox) {
      _loadHistory();
      _sub = realtime.messages.listen((m) {
        final me = ref.read(authProvider).valueOrNull?.email ?? '';
        final other = widget.email;
        final involves = (m.senderEmail == me && m.receiverEmail == other) ||
            (m.senderEmail == other && m.receiverEmail == me);
        if (!involves || !mounted) return;
        setState(() {
          if (!_messages.any((existing) => _sameMessage(existing, m))) {
            _messages.add(m);
            _sortMessages();
          }
        });
        _scrollToLatest();
      });
    }
  }

  void _loadOlderWhenAtTop() {
    if (_scrollController.hasClients &&
        _scrollController.position.pixels <= 80) {
      _loadOlderMessages();
    }
  }

  Future<void> _loadHistory() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final me = ref.read(authProvider).valueOrNull?.email;
    if (me == null || me.isEmpty) {
      setState(() {
        _loading = false;
        _error = 'Not signed in';
      });
      return;
    }
    try {
      final list = await ref.read(appRepositoryProvider).messages(
            me,
            widget.email,
            page: 0,
            size: _historyPageSize,
          );
      if (!mounted) return;
      setState(() {
        final liveMessages = List<ChatMessage>.of(_messages);
        _messages
          ..clear()
          ..addAll(list);
        for (final message in liveMessages) {
          if (!_messages.any((existing) => _sameMessage(existing, message))) {
            _messages.add(message);
          }
        }
        _sortMessages();
        _nextHistoryPage = 1;
        _hasMoreHistory = list.length == _historyPageSize;
        _loading = false;
      });
      _scrollToLatest();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = userFacingError(e);
      });
    }
  }

  Future<void> _loadOlderMessages() async {
    if (_loading || _loadingOlder || !_hasMoreHistory || _messages.isEmpty) {
      return;
    }
    final me = ref.read(authProvider).valueOrNull?.email;
    if (me == null || me.isEmpty) return;

    _loadingOlder = true;
    final oldPixels =
        _scrollController.hasClients ? _scrollController.position.pixels : 0.0;
    final oldMaxExtent = _scrollController.hasClients
        ? _scrollController.position.maxScrollExtent
        : 0.0;
    try {
      final older = await ref.read(appRepositoryProvider).messages(
            me,
            widget.email,
            page: _nextHistoryPage,
            size: _historyPageSize,
          );
      if (!mounted) return;
      setState(() {
        for (final message in older) {
          if (!_messages.any((existing) => _sameMessage(existing, message))) {
            _messages.add(message);
          }
        }
        _sortMessages();
        _nextHistoryPage++;
        _hasMoreHistory = older.length == _historyPageSize;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!_scrollController.hasClients) return;
        final extentAdded =
            _scrollController.position.maxScrollExtent - oldMaxExtent;
        _scrollController.jumpTo(oldPixels + extentAdded);
      });
    } catch (_) {
      // Keep the page index unchanged so reaching the top can retry.
    } finally {
      _loadingOlder = false;
    }
  }

  bool _sameMessage(ChatMessage first, ChatMessage second) {
    if (first.id != null && second.id != null) return first.id == second.id;
    return first.content == second.content &&
        first.senderEmail == second.senderEmail &&
        first.timestamp == second.timestamp;
  }

  void _sortMessages() {
    _messages.sort((first, second) {
      final firstTime = DateTime.tryParse(first.timestamp ?? '');
      final secondTime = DateTime.tryParse(second.timestamp ?? '');
      if (firstTime == null) return secondTime == null ? 0 : -1;
      if (secondTime == null) return 1;
      final timeOrder = firstTime.compareTo(secondTime);
      if (timeOrder != 0) return timeOrder;
      return (first.id ?? 0).compareTo(second.id ?? 0);
    });
  }

  void _scrollToLatest() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollController.hasClients) return;
      _scrollController.animateTo(
        _scrollController.position.maxScrollExtent,
        duration: const Duration(milliseconds: 220),
        curve: Curves.easeOut,
      );
    });
  }

  Future<void> _send() async {
    final me = ref.read(authProvider).valueOrNull?.email ?? '';
    final text = _input.text.trim();
    if (text.isEmpty || me.isEmpty) return;
    final msg = ChatMessage(
        senderEmail: me, receiverEmail: widget.email, content: text);
    final ok = ref.read(realtimeServiceProvider).send(msg);
    if (!ok) {
      setState(() => _sendError = 'Not connected. Try again in a moment.');
      return;
    }
    setState(() {
      _sendError = null;
      _input.clear();
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _realtimeSub?.cancel();
    _scrollController.removeListener(_loadOlderWhenAtTop);
    _input.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext c) {
    if (_isInbox) {
      return Scaffold(
          appBar: AppBar(title: const Text('Inbox')),
          body: _list<Conversation>(
            ref.watch(conversationsProvider),
            (x) => AppCard(
                child: ListTile(
                    contentPadding: const EdgeInsets.all(AppSpacing.sm),
                    leading: UserAvatar(name: x.email),
                    title: Text(x.email,
                        style: const TextStyle(fontWeight: FontWeight.w700)),
                    subtitle: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          x.latestMessage,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                        ),
                        if (x.timestamp?.isNotEmpty == true)
                          Padding(
                            padding: const EdgeInsets.only(top: AppSpacing.xs),
                            child: Text(
                              _formatTimestamp(x.timestamp!),
                              style: Theme.of(c).textTheme.bodySmall,
                            ),
                          ),
                      ],
                    ),
                    trailing: const Icon(Icons.chevron_right),
                    onTap: () =>
                        c.push('/chat/${Uri.encodeComponent(x.email)}'))),
            onRetry: () => ref.invalidate(conversationsProvider),
            onLoadMore: () =>
                ref.read(conversationsProvider.notifier).loadNextPage(),
            emptyTitle: 'No conversations yet',
            emptyMessage:
                'Start a conversation from a connected person’s profile.',
            emptyIcon: Icons.chat_bubble_outline,
          ));
    }
    final scheme = Theme.of(c).colorScheme;
    final statusBackground = switch (_realtimeState) {
      RealtimeState.connected => scheme.tertiaryContainer,
      RealtimeState.disconnected => scheme.errorContainer,
      RealtimeState.connecting ||
      RealtimeState.reconnecting =>
        scheme.secondaryContainer,
    };
    final statusForeground = switch (_realtimeState) {
      RealtimeState.connected => scheme.onTertiaryContainer,
      RealtimeState.disconnected => scheme.onErrorContainer,
      RealtimeState.connecting ||
      RealtimeState.reconnecting =>
        scheme.onSecondaryContainer,
    };
    final statusText = switch (_realtimeState) {
      RealtimeState.connected => 'Connected',
      RealtimeState.connecting => 'Connecting to messages…',
      RealtimeState.reconnecting => 'Reconnecting to messages…',
      RealtimeState.disconnected => 'Disconnected',
    };
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.email),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(36),
          child: Semantics(
            liveRegion: true,
            label: 'Realtime messaging status: $statusText',
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.lg,
                vertical: AppSpacing.sm,
              ),
              color: statusBackground,
              child: Row(
                children: [
                  if (_realtimeState == RealtimeState.connecting ||
                      _realtimeState == RealtimeState.reconnecting)
                    SizedBox(
                      width: 14,
                      height: 14,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: statusForeground,
                      ),
                    )
                  else
                    Icon(
                      _realtimeState == RealtimeState.connected
                          ? Icons.check_circle_outline
                          : Icons.cloud_off_outlined,
                      size: 16,
                      color: statusForeground,
                    ),
                  const SizedBox(width: AppSpacing.sm),
                  Text(
                    statusText,
                    style: Theme.of(c).textTheme.labelMedium?.copyWith(
                          color: statusForeground,
                          fontWeight: FontWeight.w600,
                        ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
      body: Column(
        children: [
          Expanded(
            child: _loading
                ? const LoadingState()
                : _error != null
                    ? ErrorState(
                        message: _error!,
                        onRetry: _loadHistory,
                      )
                    : _messages.isEmpty
                        ? const EmptyState(
                            title: 'Start the conversation',
                            message: 'Send a message to begin chatting.',
                            icon: Icons.chat_bubble_outline,
                          )
                        : ListView.builder(
                            controller: _scrollController,
                            padding: const EdgeInsets.all(AppSpacing.lg),
                            itemCount: _messages.length,
                            itemBuilder: (_, index) {
                              final message = _messages[index];
                              final me =
                                  ref.read(authProvider).valueOrNull?.email;
                              final mine = message.senderEmail == me;
                              final colors = Theme.of(c).colorScheme;
                              return Padding(
                                padding: const EdgeInsets.only(
                                    bottom: AppSpacing.sm),
                                child: Align(
                                  alignment: mine
                                      ? Alignment.centerRight
                                      : Alignment.centerLeft,
                                  child: ConstrainedBox(
                                    constraints: BoxConstraints(
                                      maxWidth:
                                          MediaQuery.sizeOf(c).width * .78,
                                    ),
                                    child: DecoratedBox(
                                      decoration: BoxDecoration(
                                        color: mine
                                            ? colors.primaryContainer
                                            : colors.surfaceContainerHighest,
                                        borderRadius: BorderRadius.only(
                                          topLeft: const Radius.circular(18),
                                          topRight: const Radius.circular(18),
                                          bottomLeft:
                                              Radius.circular(mine ? 18 : 4),
                                          bottomRight:
                                              Radius.circular(mine ? 4 : 18),
                                        ),
                                      ),
                                      child: Padding(
                                        padding: const EdgeInsets.symmetric(
                                          horizontal: AppSpacing.lg,
                                          vertical: AppSpacing.md,
                                        ),
                                        child: Column(
                                          crossAxisAlignment:
                                              CrossAxisAlignment.end,
                                          children: [
                                            Text(message.content),
                                            if (message.timestamp?.isNotEmpty ==
                                                true) ...[
                                              const SizedBox(
                                                  height: AppSpacing.xs),
                                              Text(
                                                _formatTimestamp(
                                                    message.timestamp!),
                                                style: Theme.of(c)
                                                    .textTheme
                                                    .labelSmall,
                                              ),
                                            ],
                                          ],
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              );
                            },
                          ),
          ),
          if (_sendError != null)
            Material(
              color: Theme.of(c).colorScheme.errorContainer,
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.lg,
                  vertical: AppSpacing.xs,
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        _sendError!,
                        style: TextStyle(
                          color: Theme.of(c).colorScheme.onErrorContainer,
                        ),
                      ),
                    ),
                    TextButton(
                      onPressed: _send,
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
            ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.sm),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      controller: _input,
                      minLines: 1,
                      maxLines: 4,
                      textCapitalization: TextCapitalization.sentences,
                      textInputAction: TextInputAction.send,
                      decoration: const InputDecoration(
                        hintText: 'Write a message',
                        prefixIcon: Icon(Icons.edit_outlined),
                      ),
                      onSubmitted: (_) => _send(),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  IconButton.filled(
                    tooltip: 'Send message',
                    onPressed: _send,
                    icon: const Icon(Icons.send),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

Widget _list<T>(
  AsyncValue<List<T>> v,
  Widget Function(T) item, {
  VoidCallback? onRetry,
  VoidCallback? onLoadMore,
  String emptyTitle = 'Nothing here yet',
  String? emptyMessage = 'New activity will appear here when it is available.',
  IconData emptyIcon = Icons.inbox_outlined,
}) =>
    v.when(
      loading: () => const LoadingState(),
      error: (e, _) => ErrorState(
        message: e.toString(),
        onRetry: onRetry,
      ),
      data: (x) => x.isEmpty
          ? EmptyState(
              icon: emptyIcon,
              title: emptyTitle,
              message: emptyMessage,
            )
          : NotificationListener<ScrollNotification>(
              onNotification: (notification) {
                if (onLoadMore != null &&
                    notification.metrics.axis == Axis.vertical &&
                    notification.metrics.pixels >=
                        notification.metrics.maxScrollExtent - 300) {
                  onLoadMore();
                }
                return false;
              },
              child: ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: x.length,
                itemBuilder: (_, i) => Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: item(x[i]),
                ),
              ),
            ),
    );
