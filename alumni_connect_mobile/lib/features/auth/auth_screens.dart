import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../../core/errors/api_exception.dart';
import '../../core/errors/error_handler.dart';
import '../app_repository.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

final _emailPattern = RegExp(r'^[^\s@]+@[^\s@]+\.[^\s@]+$');

bool _isValidEmail(String email) => _emailPattern.hasMatch(email.trim());

String registrationSuccessLocation(String email) =>
    '/verify-email?email=${Uri.encodeQueryComponent(email)}';

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

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key, this.emailVerified = false});
  final bool emailVerified;

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
  bool _needsEmailVerification = false;
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
                                            if (widget.emailVerified) ...[
                                              const SizedBox(height: 12),
                                              const Text(
                                                'Email verified. You can now sign in.',
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
                                                    setState(() {
                                                      _error = null;
                                                      _needsEmailVerification =
                                                          false;
                                                    });
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
                                            if (_needsEmailVerification)
                                              TextButton(
                                                onPressed: _busy
                                                    ? null
                                                    : () => context.go(
                                                          '/verify-email?email=${Uri.encodeQueryComponent(_email.text.trim())}',
                                                        ),
                                                child: const Text(
                                                    'Verify your email'),
                                              ),
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
      _needsEmailVerification = false;
    });
    try {
      final x = await ref
          .read(authProvider.notifier)
          .signIn(email, _pass.text, _role);
      if (!mounted) return;
      if (x == 'WAIT_APPROVAL') {
        context.go('/pending-approval');
      } else if (x == 'VERIFY_EMAIL') {
        setState(() {
          _error = 'Verify your email before signing in.';
          _needsEmailVerification = true;
        });
      } else {
        setState(() => _error = x);
      }
    } catch (error) {
      if (mounted) {
        final apiError = toApiException(error, requestPath: '/login');
        setState(() {
          _error = apiError.message;
          _needsEmailVerification =
              apiError.kind == ApiErrorKind.verificationRequired;
        });
      }
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

                                    c.go(registrationSuccessLocation(
                                        _email.text.trim()));
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

class EmailVerificationScreen extends StatefulWidget {
  const EmailVerificationScreen({super.key, this.email = ''});
  final String email;

  @override
  State<EmailVerificationScreen> createState() =>
      _EmailVerificationScreenState();
}

class _EmailVerificationScreenState extends State<EmailVerificationScreen> {
  late final TextEditingController _email;
  final _otp = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  EmailVerificationResult? _result;
  String? _error;
  String? _resendMessage;
  bool _busy = false;

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

  Future<void> _verify() async {
    if (_result != null || !_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
      _resendMessage = null;
    });
    try {
      final result = await ProviderScope.containerOf(context)
          .read(appRepositoryProvider)
          .verifyEmail(_email.text.trim(), _otp.text.trim());
      if (!mounted) return;
      setState(() => _result = result);
    } catch (error) {
      if (mounted) setState(() => _error = userFacingError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resend() async {
    if (!_isValidEmail(_email.text)) {
      setState(() => _error = 'Enter a valid email address.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _resendMessage = null;
    });
    try {
      await ProviderScope.containerOf(context)
          .read(appRepositoryProvider)
          .resendEmailVerification(_email.text.trim());
      if (!mounted) return;
      setState(() {
        _resendMessage =
            'If an account requires verification, a code will be sent shortly.';
      });
    } catch (error) {
      if (mounted) setState(() => _error = userFacingError(error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _continue() {
    final result = _result;
    if (result == null) return;
    context
        .go(result.approvalPending ? '/pending-approval' : '/login?verified=1');
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
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
                    result == null ? 'Verify your email' : 'Email verified',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    result?.message ??
                        'Enter the 6-digit verification code if you received one. If you already have an account, sign in; otherwise request a code below.',
                    textAlign: TextAlign.center,
                  ),
                  if (result == null) ...[
                    const SizedBox(height: 20),
                    TextFormField(
                      controller: _email,
                      keyboardType: TextInputType.emailAddress,
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
                      onFieldSubmitted: (_) => _busy ? null : _verify(),
                    ),
                    if (_resendMessage != null) ...[
                      const SizedBox(height: 8),
                      Text(_resendMessage!, textAlign: TextAlign.center),
                    ],
                    if (_error != null) ...[
                      const SizedBox(height: 8),
                      InlineError(_error!),
                    ],
                    const SizedBox(height: 16),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        onPressed: _busy ? null : _verify,
                        child: Text(_busy ? 'Verifying…' : 'Verify email'),
                      ),
                    ),
                    TextButton(
                      onPressed: _busy ? null : _resend,
                      child: const Text('Resend verification code'),
                    ),
                    TextButton(
                      onPressed: _busy ? null : () => context.go('/login'),
                      child: const Text('Back to sign in'),
                    ),
                  ] else ...[
                    const SizedBox(height: 24),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        onPressed: _continue,
                        child: Text(result.approvalPending
                            ? 'Continue to approval status'
                            : 'Continue to sign in'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
