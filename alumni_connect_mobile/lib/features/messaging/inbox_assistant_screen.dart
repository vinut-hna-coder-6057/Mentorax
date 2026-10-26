import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';
import 'conversation_list_tile.dart';
import 'inbox_category.dart';

class InboxAssistantScreen extends ConsumerStatefulWidget {
  const InboxAssistantScreen({super.key});

  @override
  ConsumerState<InboxAssistantScreen> createState() =>
      _InboxAssistantScreenState();
}

class _InboxAssistantScreenState extends ConsumerState<InboxAssistantScreen> {
  InboxCategory? _selectedCategory;
  final Map<String, Future<_ConversationClassification>> _classificationCache =
      {};
  int _classificationGeneration = 0;

  @override
  Widget build(BuildContext context) {
    final conversations = ref.watch(conversationsProvider(null));
    final category = _selectedCategory;
    return Scaffold(
      appBar: AppBar(
        leading: category == null
            ? null
            : IconButton(
                tooltip: 'Back to categories',
                icon: const Icon(Icons.arrow_back),
                onPressed: () => setState(() => _selectedCategory = null),
              ),
        title: Text(category?.label ?? 'Inbox assistant'),
      ),
      body: category == null
          ? _buildCategories(context, conversations)
          : conversations.when(
              loading: () => const LoadingState(),
              error: (error, _) => ErrorState(
                message: userFacingError(error),
                onRetry: () => ref
                    .read(conversationsProvider(null).notifier)
                    .loadFirstPage(),
              ),
              data: (items) => _buildConversationResults(
                context,
                items,
                category,
              ),
            ),
    );
  }

  Widget _buildCategories(
    BuildContext context,
    AsyncValue<List<Conversation>> conversations,
  ) {
    final categories = InboxCategory.values;
    return ListView(
      padding: const EdgeInsets.all(AppSpacing.lg),
      children: [
        Text(
          'Browse your conversations by topic.',
          style: Theme.of(context).textTheme.bodyLarge,
        ),
        const SizedBox(height: AppSpacing.md),
        for (final category in categories)
          Padding(
            padding: const EdgeInsets.only(bottom: AppSpacing.sm),
            child: AppCard(
              padding: EdgeInsets.zero,
              child: ListTile(
                title: Text(category.label),
                trailing: const Icon(Icons.chevron_right),
                onTap: () => setState(() => _selectedCategory = category),
              ),
            ),
          ),
        if (conversations.hasError)
          Padding(
            padding: const EdgeInsets.only(top: AppSpacing.md),
            child: Text(
              userFacingError(conversations.error!),
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
      ],
    );
  }

  Widget _buildConversationResults(
    BuildContext context,
    List<Conversation> items,
    InboxCategory category,
  ) {
    final notifier = ref.read(conversationsProvider(null).notifier);
    if (category == InboxCategory.allMessages) {
      return _buildConversationList(
        context,
        items,
        hasMore: notifier.hasMore,
        isLoadingMore: notifier.isLoadingMore,
      );
    }

    return FutureBuilder<List<_ConversationClassification>>(
      key: ValueKey(
        items.map((conversation) {
          return [
            conversation.email,
            conversation.timestamp ?? '',
            conversation.latestMessage,
          ].join('\u0000');
        }).join('\u0001'),
      ),
      future: _classifyConversations(items),
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return ErrorState(
            message: userFacingError(snapshot.error!),
            onRetry: () => setState(() {
              _classificationGeneration++;
              _classificationCache.clear();
            }),
          );
        }
        if (!snapshot.hasData || snapshot.data!.length != items.length) {
          return const LoadingState();
        }

        final classifications = snapshot.data!;
        final matching = <Conversation>[];
        var contextFailures = 0;
        String? firstContextError;
        for (var index = 0; index < items.length; index++) {
          final result = classifications[index];
          if (result.historyError != null) {
            contextFailures++;
            firstContextError ??= result.historyError;
          }
          if (result.category == category) matching.add(items[index]);
        }
        return Column(
          children: [
            if (contextFailures > 0)
              Material(
                color: Theme.of(context).colorScheme.errorContainer,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: AppSpacing.lg,
                    vertical: AppSpacing.sm,
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          '$contextFailures recent conversation history '
                          'request(s) failed: '
                          '$firstContextError. Those conversations use their '
                          'latest message preview for classification.',
                        ),
                      ),
                      TextButton(
                        onPressed: () => setState(() {
                          _classificationGeneration++;
                          _classificationCache.clear();
                        }),
                        child: const Text('Retry'),
                      ),
                    ],
                  ),
                ),
              ),
            Expanded(
              child: _buildConversationList(
                context,
                matching,
                hasMore: notifier.hasMore,
                isLoadingMore: notifier.isLoadingMore,
                emptyTitle: items.isEmpty
                    ? 'No conversations yet'
                    : 'No conversations in this category yet',
                emptyMessage: items.isEmpty
                    ? 'Your existing conversations will appear here.'
                    : 'Load more conversations to keep looking.',
              ),
            ),
          ],
        );
      },
    );
  }

  Widget _buildConversationList(
    BuildContext context,
    List<Conversation> items, {
    required bool hasMore,
    required bool isLoadingMore,
    String emptyTitle = 'No conversations yet',
    String emptyMessage =
        'Open a connected person’s profile and choose Message to start a conversation.',
  }) {
    final itemCount = items.isEmpty
        ? 1 + (hasMore ? 1 : 0)
        : items.length + (hasMore ? 1 : 0);
    return RefreshIndicator(
      onRefresh: () =>
          ref.read(conversationsProvider(null).notifier).loadFirstPage(),
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          if (notification.metrics.axis == Axis.vertical &&
              notification.metrics.pixels >=
                  notification.metrics.maxScrollExtent - 300) {
            unawaited(
              ref.read(conversationsProvider(null).notifier).loadNextPage(),
            );
          }
          return false;
        },
        child: ListView.builder(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(AppSpacing.lg),
          itemCount: itemCount,
          itemBuilder: (context, index) {
            if (items.isEmpty && index == 0) {
              return SizedBox(
                height: MediaQuery.sizeOf(context).height * .5,
                child: EmptyState(
                  title: emptyTitle,
                  message: emptyMessage,
                  icon: Icons.chat_bubble_outline,
                ),
              );
            }
            if (hasMore && index == itemCount - 1) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  child: isLoadingMore
                      ? const CircularProgressIndicator()
                      : OutlinedButton(
                          onPressed: () => ref
                              .read(conversationsProvider(null).notifier)
                              .loadNextPage(),
                          child: const Text('Load more conversations'),
                        ),
                ),
              );
            }
            final conversation = items[index];
            return ConversationListTile(
              conversation: conversation,
              onTap: conversation.email.isEmpty
                  ? null
                  : () => context.push(
                        '/chat/${Uri.encodeComponent(conversation.email)}',
                      ),
            );
          },
        ),
      ),
    );
  }

  Future<List<_ConversationClassification>> _classifyConversations(
    List<Conversation> conversations,
  ) async {
    final generation = _classificationGeneration;
    final userEmail = ref.read(authProvider).valueOrNull?.email;
    final results = List<_ConversationClassification?>.filled(
      conversations.length,
      null,
    );
    var nextIndex = 0;

    Future<void> worker() async {
      while (true) {
        final index = nextIndex++;
        if (index >= conversations.length) return;
        results[index] = await _classificationFor(
          conversations[index],
          userEmail,
          generation,
        );
      }
    }

    await Future.wait(
      List.generate(
        conversations.length < 4 ? conversations.length : 4,
        (_) => worker(),
      ),
    );
    return results.cast<_ConversationClassification>();
  }

  Future<_ConversationClassification> _classificationFor(
    Conversation conversation,
    String? userEmail,
    int generation,
  ) {
    final key = [
      conversation.email.toLowerCase(),
      conversation.timestamp ?? '',
      conversation.latestMessage,
    ].join('\u0000');
    return _classificationCache.putIfAbsent(key, () async {
      final latestCategory =
          classifyConversationMessages([conversation.latestMessage]);
      if (latestCategory != InboxCategory.other ||
          userEmail == null ||
          userEmail.isEmpty ||
          conversation.email.isEmpty) {
        return _ConversationClassification(latestCategory);
      }
      try {
        final recentMessages = await ref.read(appRepositoryProvider).messages(
              userEmail,
              conversation.email,
              page: 0,
              size: 20,
            );
        if (!mounted || generation != _classificationGeneration) {
          return _ConversationClassification(latestCategory);
        }
        return _ConversationClassification(
          classifyConversationMessages([
            conversation.latestMessage,
            ...recentMessages.map((message) => message.content),
          ]),
        );
      } catch (error) {
        return _ConversationClassification(
          latestCategory,
          historyError: userFacingError(error),
        );
      }
    });
  }
}

class _ConversationClassification {
  const _ConversationClassification(
    this.category, {
    this.historyError,
  });

  final InboxCategory category;
  final String? historyError;
}
