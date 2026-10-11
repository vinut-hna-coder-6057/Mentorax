import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import '../../shared/widgets/ui_components.dart';

String formatTimestamp(String value) {
  final parsed = DateTime.tryParse(value);
  if (parsed == null) return value;
  return DateFormat.yMMMd().add_jm().format(parsed.toLocal());
}

Widget asyncList<T>(
  AsyncValue<List<T>> v,
  Widget Function(T) item, {
  VoidCallback? onRetry,
  VoidCallback? onLoadMore,
  String emptyTitle = 'Nothing here yet',
  String? emptyMessage = 'New activity will appear here when it is available.',
  IconData emptyIcon = Icons.inbox_outlined,
}) =>
    v.when(
      loading: () => const LoadingState(),
      error: (e, _) => ErrorState(
        message: e.toString(),
        onRetry: onRetry,
      ),
      data: (x) => x.isEmpty
          ? EmptyState(
              icon: emptyIcon,
              title: emptyTitle,
              message: emptyMessage,
            )
          : NotificationListener<ScrollNotification>(
              onNotification: (notification) {
                if (onLoadMore != null &&
                    notification.metrics.axis == Axis.vertical &&
                    notification.metrics.pixels >=
                        notification.metrics.maxScrollExtent - 300) {
                  onLoadMore();
                }
                return false;
              },
              child: ListView.builder(
                padding: const EdgeInsets.all(16),
                itemCount: x.length,
                itemBuilder: (_, i) => Padding(
                  padding: const EdgeInsets.only(bottom: 10),
                  child: item(x[i]),
                ),
              ),
            ),
    );
