import 'package:flutter/material.dart';

class AppColors {
  static const navy = Color(0xff102a43);
  static const blue = Color(0xff1565c0);
  static const teal = Color(0xff00897b);
  static const background = Color(0xfff5f8fc);
  static const ink = Color(0xff172b4d);
  static const muted = Color(0xff6b7c93);
  static const success = Color(0xff16803c);
  static const warning = Color(0xffb26a00);
}

class AppCard extends StatelessWidget {
  const AppCard({super.key, required this.child, this.padding});
  final Widget child;
  final EdgeInsetsGeometry? padding;

  @override
  Widget build(BuildContext context) => Card(
        child: Padding(
          padding: padding ?? const EdgeInsets.all(18),
          child: child,
        ),
      );
}

class AppSectionTitle extends StatelessWidget {
  const AppSectionTitle(this.title, {super.key, this.action});
  final String title;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Expanded(
            child: Text(title, style: Theme.of(context).textTheme.titleLarge),
          ),
          if (action != null) action!,
        ],
      );
}

class StatusBadge extends StatelessWidget {
  const StatusBadge(this.label, {super.key});
  final String label;

  @override
  Widget build(BuildContext context) {
    final value = label.toUpperCase();
    final color = value == 'APPROVED' ||
            value == 'ACCEPTED' ||
            value == 'CONNECTED' ||
            value == 'READ'
        ? AppColors.success
        : value == 'REJECTED' || value == 'BLOCKED'
            ? Theme.of(context).colorScheme.error
            : AppColors.warning;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color.withValues(alpha: .1),
        borderRadius: BorderRadius.circular(30),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
        child: Text(value,
            style: TextStyle(
                color: color, fontSize: 11, fontWeight: FontWeight.w700)),
      ),
    );
  }
}

class UserAvatar extends StatelessWidget {
  const UserAvatar({super.key, this.name, this.imageUrl, this.radius = 24});
  final String? name;
  final String? imageUrl;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final initials = (name ?? 'A')
        .trim()
        .split(RegExp(r'\s+'))
        .where((part) => part.isNotEmpty)
        .take(2)
        .map((part) => part[0].toUpperCase())
        .join();
    return CircleAvatar(
      radius: radius,
      backgroundColor: Theme.of(context).colorScheme.primaryContainer,
      backgroundImage: imageUrl == null || imageUrl!.isEmpty
          ? null
          : NetworkImage(imageUrl!),
      child: imageUrl == null || imageUrl!.isEmpty
          ? Text(initials,
              style: TextStyle(
                  color: Theme.of(context).colorScheme.primary,
                  fontWeight: FontWeight.w800))
          : null,
    );
  }
}

class EmptyState extends StatelessWidget {
  const EmptyState(
      {super.key, required this.title, this.message, this.icon = Icons.inbox});
  final String title;
  final String? message;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon,
                  size: 46,
                  color: Theme.of(context)
                      .colorScheme
                      .primary
                      .withValues(alpha: .65)),
              const SizedBox(height: 14),
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              if (message != null) ...[
                const SizedBox(height: 6),
                Text(message!,
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium),
              ],
            ],
          ),
        ),
      );
}

String userFacingError(Object error) {
  final message = error.toString().replaceFirst('Exception: ', '').trim();
  if (message.isEmpty || message.contains('DioException')) {
    return 'Something went wrong. Please try again.';
  }
  if (message.length > 140 || message.contains('SocketException')) {
    return 'We could not reach the service. Check your connection and try again.';
  }
  return message;
}

class ErrorState extends StatelessWidget {
  const ErrorState(
      {super.key, required this.message, this.onRetry, this.title = 'Unable to load'});
  final String title;
  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) => Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.cloud_off_outlined,
                  size: 46, color: Theme.of(context).colorScheme.error),
              const SizedBox(height: 14),
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 6),
              Text(message,
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodyMedium),
              if (onRetry != null) ...[
                const SizedBox(height: 16),
                OutlinedButton.icon(
                    onPressed: onRetry,
                    icon: const Icon(Icons.refresh),
                    label: const Text('Try again')),
              ],
            ],
          ),
        ),
      );
}

class InlineError extends StatelessWidget {
  const InlineError(this.message, {super.key});
  final String message;

  @override
  Widget build(BuildContext context) => Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.error_outline,
              size: 18, color: Theme.of(context).colorScheme.error),
          const SizedBox(width: 8),
          Expanded(
              child: Text(message,
                  style: TextStyle(
                      color: Theme.of(context).colorScheme.error))),
        ],
      );
}

class LoadingState extends StatelessWidget {
  const LoadingState({super.key});
  @override
  Widget build(BuildContext context) => const Center(
        child:
            SizedBox(width: 28, height: 28, child: CircularProgressIndicator()),
      );
}

class AppTextField extends StatelessWidget {
  const AppTextField(
      {super.key,
      required this.controller,
      required this.label,
      this.icon,
      this.obscureText = false,
      this.keyboardType});
  final TextEditingController controller;
  final String label;
  final IconData? icon;
  final bool obscureText;
  final TextInputType? keyboardType;

  @override
  Widget build(BuildContext context) => TextField(
        controller: controller,
        obscureText: obscureText,
        keyboardType: keyboardType,
        decoration: InputDecoration(
          labelText: label,
          prefixIcon: icon == null ? null : Icon(icon),
        ),
      );
}

Widget responsiveContent(BuildContext context, Widget child) => Center(
        child: ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 720),
      child: child,
    ));
