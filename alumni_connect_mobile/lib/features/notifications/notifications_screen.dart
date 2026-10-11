import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';
import '../shared/presentation_helpers.dart';

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
            child: asyncList<NotificationItem>(
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
                      : Text(formatTimestamp(notification.timestamp!)),
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
