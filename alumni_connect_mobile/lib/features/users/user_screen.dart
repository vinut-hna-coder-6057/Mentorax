import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

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
      final refreshedConnections =
          await ref.refresh(connectionsProvider.future);
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
    final currentUserId = currentUser?.id;
    final isOwnProfile = currentUser?.id == id;
    final connections = connectionState.valueOrNull ?? const <ConnectionItem>[];
    final matches = connections
        .where((x) => x.requester?.id == id || x.receiver?.id == id)
        .toList();
    final connection = matches.isEmpty ? null : matches.first;
    final status = connection?.status.toUpperCase();
    final chatEmail = connection?.requester?.id == id
        ? connection?.requester?.email
        : connection?.receiver?.email;

    void openChat() {
      if (chatEmail != null && chatEmail.isNotEmpty) {
        c.push('/chat/${Uri.encodeComponent(chatEmail)}');
      }
    }

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
              onPressed: openChat,
              icon: const Icon(Icons.chat_bubble_outline),
              label: const Text('Message'),
            )
          else if (!isOwnProfile &&
              status == 'PENDING' &&
              connection?.requester?.id == currentUserId)
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
                    if ((isOwnProfile || currentUser?.role == UserRole.admin) &&
                        user.email.isNotEmpty)
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
                  onPressed: openChat,
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
