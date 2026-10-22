import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../admin/approvals_screen.dart';
import '../directory/directory_screen.dart';
import '../events/events_screens.dart';
import '../profile/profile_screen.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

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
            if (user?.role == UserRole.student)
              _QuickAction(
                icon: Icons.auto_awesome_outlined,
                label: 'Find a mentor',
                onTap: () => c.push('/student/mentor-discovery'),
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
