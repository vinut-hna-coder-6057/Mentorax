import 'package:flutter/material.dart';

import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';
import '../shared/presentation_helpers.dart';

class ConversationListTile extends StatelessWidget {
  const ConversationListTile({
    required this.conversation,
    required this.onTap,
    super.key,
  });

  final Conversation conversation;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final displayName = conversation.displayName?.trim();
    final hasDisplayName = displayName?.isNotEmpty == true;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: AppCard(
        padding: EdgeInsets.zero,
        child: ListTile(
          minVerticalPadding: AppSpacing.md,
          contentPadding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg,
            vertical: AppSpacing.sm,
          ),
          leading: UserAvatar(
            name: hasDisplayName ? displayName : conversation.email,
            imageUrl: conversation.profileImage,
          ),
          title: Text(
            hasDisplayName ? displayName! : conversation.email,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontWeight: FontWeight.w700),
          ),
          subtitle: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (hasDisplayName)
                Text(
                  conversation.email,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              Text(
                conversation.latestMessage.isEmpty
                    ? 'No message preview available'
                    : conversation.latestMessage,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
              if (conversation.timestamp?.isNotEmpty == true)
                Padding(
                  padding: const EdgeInsets.only(top: AppSpacing.xs),
                  child: Text(
                    formatTimestamp(conversation.timestamp!),
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
            ],
          ),
          trailing: const Icon(Icons.chevron_right),
          onTap: onTap,
        ),
      ),
    );
  }
}
