import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

class DirectoryScreen extends ConsumerStatefulWidget {
  const DirectoryScreen({super.key});

  @override
  ConsumerState<DirectoryScreen> createState() => _DirectoryScreenState();
}

class _DirectoryScreenState extends ConsumerState<DirectoryScreen> {
  UserRole _role = UserRole.alumni;
  final _search = TextEditingController();
  final _scrollController = ScrollController();

  final List<User> _users = [];

  int _page = 0;
  static const int _pageSize = 50;

  bool _isLoading = true;
  bool _isLoadingMore = false;
  bool _hasMore = true;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _loadFirstPage();
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    _search.dispose();
    super.dispose();
  }

  Future<void> _loadFirstPage() async {
    if (!mounted) return;

    setState(() {
      _users.clear();
      _page = 0;
      _hasMore = true;
      _isLoading = true;
      _isLoadingMore = false;
      _error = null;
    });

    try {
      final results = await ref.read(appRepositoryProvider).users(
            _role,
            page: 0,
            size: _pageSize,
          );

      if (!mounted) return;

      setState(() {
        _users.addAll(results);
        _hasMore = results.length == _pageSize;
        _isLoading = false;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _error = error;
        _isLoading = false;
      });
    }
  }

  Future<void> _loadNextPage() async {
    if (!mounted || _isLoading || _isLoadingMore || !_hasMore) {
      return;
    }

    setState(() {
      _isLoadingMore = true;
    });

    final nextPage = _page + 1;

    try {
      final results = await ref.read(appRepositoryProvider).users(
            _role,
            page: nextPage,
            size: _pageSize,
          );

      if (!mounted) return;

      setState(() {
        _users.addAll(results);
        _page = nextPage;
        _hasMore = results.length == _pageSize;
        _isLoadingMore = false;
      });
    } catch (error) {
      if (!mounted) return;

      setState(() {
        _isLoadingMore = false;
        _error = error;
      });
    }
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;

    final position = _scrollController.position;

    if (position.pixels >= position.maxScrollExtent - 300) {
      _loadNextPage();
    }
  }

  @override
  Widget build(BuildContext c) {
    final query = _search.text.trim().toLowerCase();

    return Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 1000),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.lg),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Discover people',
                style: Theme.of(c).textTheme.headlineSmall,
              ),
              const SizedBox(height: 4),
              Text(
                'Find students and alumni and grow your professional network.',
                style: Theme.of(c).textTheme.bodyMedium,
              ),
              const SizedBox(height: 16),
              TextField(
                controller: _search,
                textInputAction: TextInputAction.search,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  labelText: 'Search people',
                  prefixIcon: const Icon(Icons.search),
                  suffixIcon: query.isEmpty
                      ? null
                      : IconButton(
                          tooltip: 'Clear search',
                          onPressed: () {
                            _search.clear();
                            setState(() {});
                          },
                          icon: const Icon(Icons.close),
                        ),
                ),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<UserRole>(
                initialValue: _role,
                decoration: const InputDecoration(
                  labelText: 'Browse',
                  prefixIcon: Icon(Icons.filter_list),
                ),
                items: const [
                  DropdownMenuItem(
                    value: UserRole.alumni,
                    child: Text('Alumni'),
                  ),
                  DropdownMenuItem(
                    value: UserRole.student,
                    child: Text('Students'),
                  ),
                ],
                onChanged: (value) {
                  if (value == null || value == _role) return;

                  setState(() {
                    _role = value;
                  });

                  _loadFirstPage();
                },
              ),
              const SizedBox(height: 12),
              Expanded(
                child: _isLoading
                    ? const LoadingState()
                    : _error != null && _users.isEmpty
                        ? ErrorState(
                            message: userFacingError(_error!),
                            onRetry: _loadFirstPage,
                          )
                        : _buildUserGrid(c, query),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildUserGrid(BuildContext c, String query) {
    final filtered = _users.where((user) {
      if (query.isEmpty) return true;

      final haystack = [
        user.name,
        user.email,
        user.company,
        user.jobRole,
        user.branch,
        user.location,
        user.skills,
      ].whereType<String>().join(' ').toLowerCase();

      return haystack.contains(query);
    }).toList();

    if (filtered.isEmpty) {
      return EmptyState(
        title: query.isEmpty
            ? 'No ${_role.name}s to show'
            : 'No matches found in loaded users',
        message: query.isEmpty
            ? 'People will appear here when they join the community.'
            : 'Try another name, skill, company, or location.',
        icon: Icons.person_search_outlined,
      );
    }

    return GridView.builder(
      controller: _scrollController,
      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
      gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: 480,
        childAspectRatio: 2.7,
        crossAxisSpacing: AppSpacing.md,
        mainAxisSpacing: AppSpacing.md,
      ),
      itemCount: filtered.length + (_isLoadingMore ? 1 : 0),
      itemBuilder: (context, index) {
        if (index >= filtered.length) {
          return const Center(
            child: Padding(
              padding: EdgeInsets.all(AppSpacing.md),
              child: CircularProgressIndicator(),
            ),
          );
        }

        final u = filtered[index];

        final details = [
          if (u.jobRole?.isNotEmpty == true) u.jobRole!,
          if (u.company?.isNotEmpty == true) u.company!,
          if (u.branch?.isNotEmpty == true) u.branch!,
          if (u.passoutYear?.isNotEmpty == true) 'Class of ${u.passoutYear}',
          if (u.location?.isNotEmpty == true) u.location!,
        ];
        final text = details.isEmpty ? u.email : details.join(' · ');

        return AppCard(
          child: InkWell(
            onTap: () => c.push('/user/${u.id}'),
            borderRadius: BorderRadius.circular(AppRadius.lg),
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  UserAvatar(
                    name: u.name,
                    imageUrl: u.profileImage,
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      mainAxisAlignment: MainAxisAlignment.center,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          u.name,
                          style: Theme.of(context).textTheme.titleMedium,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: AppSpacing.xs),
                        Text(
                          text,
                          maxLines: 2,
                          softWrap: true,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ],
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(left: AppSpacing.xs),
                    child: Icon(
                      Icons.chevron_right,
                      size: 20,
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
