import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

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
      final refreshedConnections =
          await ref.refresh(connectionsProvider.future);
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
