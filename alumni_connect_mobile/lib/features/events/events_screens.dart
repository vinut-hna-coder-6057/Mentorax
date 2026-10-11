import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';
import '../shared/presentation_helpers.dart';

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
            child: asyncList<EventItem>(
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
                                      event.status?.toUpperCase() != 'PENDING'
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
                              onPressed: _workingEventIds.contains(event.id) ||
                                      event.status?.toUpperCase() != 'PENDING'
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
  final repository = ref.read(appRepositoryProvider);
  final result = await showDialog<EventItem>(
    context: context,
    builder: (_) => _EventEditorDialog(
      existing: existing,
      onSave: (event) => existing == null
          ? repository.createEvent(event)
          : repository.updateEvent(event),
    ),
  );
  if (result == null || !context.mounted) return;

  ref.invalidate(eventsProvider);
  ref.invalidate(adminEventsProvider);
  ScaffoldMessenger.of(context).showSnackBar(
    SnackBar(
      content: Text(
        existing == null ? 'Event created' : 'Event updated',
      ),
    ),
  );
}

class _EventEditorDialog extends StatefulWidget {
  const _EventEditorDialog({
    required this.existing,
    required this.onSave,
  });

  final EventItem? existing;
  final Future<EventItem> Function(EventItem event) onSave;

  @override
  State<_EventEditorDialog> createState() => _EventEditorDialogState();
}

class _EventEditorDialogState extends State<_EventEditorDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _title;
  late final TextEditingController _description;
  late final TextEditingController _date;
  late final TextEditingController _location;
  late final TextEditingController _category;
  late final TextEditingController _link;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final existing = widget.existing;
    _title = TextEditingController(text: existing?.title);
    _description = TextEditingController(text: existing?.description);
    _date = TextEditingController(text: existing?.eventDate);
    _location = TextEditingController(text: existing?.location);
    _category = TextEditingController(text: existing?.category);
    _link = TextEditingController(text: existing?.meetingLink);
  }

  @override
  void dispose() {
    _title.dispose();
    _description.dispose();
    _date.dispose();
    _location.dispose();
    _category.dispose();
    _link.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_saving || !(_formKey.currentState?.validate() ?? false)) return;

    final existing = widget.existing;
    final event = EventItem(
      id: existing?.id ?? 0,
      title: _title.text.trim(),
      description: _description.text.trim(),
      eventDate: _date.text.trim(),
      location: _location.text.trim(),
      category: _category.text.trim(),
      meetingLink: _link.text.trim(),
    );

    setState(() => _saving = true);
    try {
      final saved = await widget.onSave(event);
      if (mounted) Navigator.of(context).pop(saved);
    } catch (error) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(userFacingError(error))),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final existing = widget.existing;
    return AlertDialog(
      title: Text(existing == null ? 'Create event' : 'Edit event'),
      content: SingleChildScrollView(
        child: Form(
          key: _formKey,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextFormField(
                controller: _title,
                decoration: const InputDecoration(labelText: 'Title'),
                validator: (value) =>
                    value == null || value.trim().isEmpty ? 'Required' : null,
              ),
              TextFormField(
                controller: _description,
                decoration: const InputDecoration(labelText: 'Description'),
              ),
              TextFormField(
                controller: _date,
                decoration: const InputDecoration(labelText: 'Date and time'),
              ),
              TextFormField(
                controller: _location,
                decoration: const InputDecoration(labelText: 'Location'),
              ),
              TextFormField(
                controller: _category,
                decoration: const InputDecoration(labelText: 'Category'),
              ),
              TextFormField(
                controller: _link,
                decoration: const InputDecoration(labelText: 'Meeting link'),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: _saving ? null : () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: _saving ? null : _submit,
          child: _saving
              ? const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : Text(existing == null ? 'Create' : 'Save'),
        ),
      ],
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
  @override
  Widget build(BuildContext c) {
    final id = widget.id;
    final isAdmin = ref.watch(authProvider).valueOrNull?.role == UserRole.admin;
    final events = ref.watch(isAdmin ? adminEventsProvider : eventsProvider);
    final registrationStatus = ref.watch(eventRegistrationStatusProvider(id));
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
              if (event.status?.toUpperCase() == 'APPROVED')
                registrationStatus.when(
                  loading: () => const Center(
                    child: CircularProgressIndicator(),
                  ),
                  error: (error, _) => Column(
                    children: [
                      Text(userFacingError(error)),
                      TextButton(
                        onPressed: () =>
                            ref.invalidate(eventRegistrationStatusProvider(id)),
                        child: const Text('Retry registration status'),
                      ),
                    ],
                  ),
                  data: (registered) => SizedBox(
                    width: double.infinity,
                    child: registered
                        ? OutlinedButton.icon(
                            onPressed: _registering
                                ? null
                                : () => _updateRegistration(
                                      id,
                                      register: false,
                                      refreshProvider: () async {
                                        final refreshedStatus =
                                            await ref.refresh(
                                                eventRegistrationStatusProvider(
                                                        id)
                                                    .future);
                                        if (refreshedStatus) {
                                          throw StateError(
                                            'Event registration status did not update.',
                                          );
                                        }
                                        await ref
                                            .read(eventsProviderToRefresh
                                                .notifier)
                                            .loadFirstPage();
                                      },
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
                            label: Text(_registering
                                ? 'Please wait…'
                                : 'Cancel registration'),
                          )
                        : FilledButton.icon(
                            onPressed: _registering
                                ? null
                                : () => _updateRegistration(
                                      id,
                                      register: true,
                                      refreshProvider: () async {
                                        final refreshedStatus =
                                            await ref.refresh(
                                                eventRegistrationStatusProvider(
                                                        id)
                                                    .future);
                                        if (!refreshedStatus) {
                                          throw StateError(
                                            'Event registration status did not update.',
                                          );
                                        }
                                        await ref
                                            .read(eventsProviderToRefresh
                                                .notifier)
                                            .loadFirstPage();
                                      },
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
                            label: Text(
                                _registering ? 'Please wait…' : 'Register'),
                          ),
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
    required Future<void> Function() refreshProvider,
  }) async {
    setState(() => _registering = true);
    try {
      final repository = ref.read(appRepositoryProvider);
      if (register) {
        await repository.registerEvent(id);
      } else {
        await repository.cancelRegistration(id);
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
      if (mounted) setState(() => _registering = false);
      return;
    }

    if (!mounted) return;
    try {
      await refreshProvider();
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Registration was saved, but the event could not be refreshed: '
              '${userFacingError(error)}',
            ),
          ),
        );
      }
      if (mounted) setState(() => _registering = false);
      return;
    }

    if (mounted) {
      setState(() => _registering = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
              register ? 'Registered for event' : 'Registration cancelled'),
        ),
      );
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
                              : Text(formatTimestamp(attendee.registeredAt!)),
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
