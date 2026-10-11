import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:flutter/services.dart';
import '../../app/providers.dart';
import '../../core/network/realtime_service.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';
import '../shared/presentation_helpers.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key, required this.email});
  final String email;
  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

bool isValidChatMessageContent(String content) =>
    content.trim().isNotEmpty && content.length <= 2000;

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final _input = TextEditingController();
  final _scrollController = ScrollController();
  final _messages = <ChatMessage>[];
  StreamSubscription<RealtimeSessionEvent<ChatMessage>>? _sub;
  StreamSubscription<RealtimeState>? _realtimeSub;
  StreamSubscription<RealtimeFailure>? _realtimeErrorSub;
  RealtimeState _realtimeState = RealtimeState.disconnected;
  bool _loading = true;
  bool _loadingOlder = false;
  bool _sending = false;
  bool _hasMoreHistory = true;
  int _nextHistoryPage = 1;
  static const _historyPageSize = 50;
  String? _error;
  String? _sendError;
  String? _pendingContent;

  bool get _isInbox => widget.email == 'inbox';

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_loadOlderWhenAtTop);
    final realtime = ref.read(realtimeServiceProvider);
    _realtimeState = realtime.current;
    _realtimeSub = realtime.states.listen((state) {
      if (!mounted) return;
      setState(() {
        _realtimeState = state;
        if (state != RealtimeState.connected && _pendingContent != null) {
          _pendingContent = null;
          _sending = false;
          _sendError =
              'Delivery could not be confirmed. Check the conversation before retrying.';
        }
      });
    });
    _realtimeErrorSub = realtime.errors.listen((failure) {
      if (!mounted || _pendingContent == null) return;
      setState(() {
        _pendingContent = null;
        _sending = false;
        _sendError = switch (failure) {
          RealtimeFailure.authentication =>
            'Your messaging session was rejected. Sign in again to continue.',
          RealtimeFailure.connection =>
            'Message connection failed. Your draft was kept.',
          RealtimeFailure.subscription =>
            'Unable to subscribe to messages. Your draft was kept.',
          RealtimeFailure.send =>
            'Message could not be sent. Your draft was kept.',
        };
      });
    });
    if (!_isInbox) {
      _loadHistory();
      _sub = realtime.messageEvents.listen((event) {
        if (!realtime.isCurrentSession(
          event.sessionVersion,
          event.connectionVersion,
        )) {
          return;
        }
        final m = event.value;
        final me = ref.read(authProvider).valueOrNull?.email ?? '';
        final other = widget.email;
        final involves = (m.senderEmail == me && m.receiverEmail == other) ||
            (m.senderEmail == other && m.receiverEmail == me);
        if (!involves || !mounted) return;
        final confirmsPendingSend = _pendingContent != null &&
            m.senderEmail == me &&
            m.receiverEmail == other &&
            m.content == _pendingContent;
        setState(() {
          if (!_messages.any((existing) => _sameMessage(existing, m))) {
            _messages.add(m);
            _sortMessages();
          }
          if (confirmsPendingSend) {
            _pendingContent = null;
            _sending = false;
            _sendError = null;
            if (_input.text == m.content) _input.clear();
          }
        });
        ref.invalidate(conversationsProvider);
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
    if (_sending) return;
    final me = ref.read(authProvider).valueOrNull?.email ?? '';
    final text = _input.text.trim();
    if (text.isEmpty || me.isEmpty) return;
    if (!isValidChatMessageContent(text)) {
      setState(() {
        _sendError = 'Messages must be 2,000 characters or fewer.';
      });
      return;
    }
    final msg = ChatMessage(
        senderEmail: me, receiverEmail: widget.email, content: text);
    setState(() {
      _sendError = null;
      _pendingContent = text;
      _sending = true;
    });
    final realtime = ref.read(realtimeServiceProvider);
    final ok = realtime.send(msg);
    if (!ok) {
      setState(() {
        _pendingContent = null;
        _sending = false;
        _sendError = 'Not connected. Try again in a moment.';
      });
      return;
    }
  }

  @override
  void dispose() {
    _sub?.cancel();
    _realtimeSub?.cancel();
    _realtimeErrorSub?.cancel();
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
          body: asyncList<Conversation>(
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
                              formatTimestamp(x.timestamp!),
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
      RealtimeState.authenticationRejected ||
      RealtimeState.subscriptionFailed =>
        scheme.errorContainer,
      RealtimeState.connecting ||
      RealtimeState.reconnecting =>
        scheme.secondaryContainer,
    };
    final statusForeground = switch (_realtimeState) {
      RealtimeState.connected => scheme.onTertiaryContainer,
      RealtimeState.disconnected => scheme.onErrorContainer,
      RealtimeState.authenticationRejected ||
      RealtimeState.subscriptionFailed =>
        scheme.onErrorContainer,
      RealtimeState.connecting ||
      RealtimeState.reconnecting =>
        scheme.onSecondaryContainer,
    };
    final statusText = switch (_realtimeState) {
      RealtimeState.connected => 'Connected',
      RealtimeState.connecting => 'Connecting to messages…',
      RealtimeState.reconnecting => 'Reconnecting to messages…',
      RealtimeState.disconnected => 'Disconnected',
      RealtimeState.authenticationRejected =>
        'Messaging authentication failed. Sign in again.',
      RealtimeState.subscriptionFailed => 'Unable to subscribe to messages.',
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
                                                formatTimestamp(
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
                      maxLength: 2000,
                      maxLengthEnforcement: MaxLengthEnforcement.enforced,
                      readOnly: _sending,
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
                    onPressed: _sending ? null : _send,
                    icon: _sending
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.send),
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
