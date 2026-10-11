import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

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
