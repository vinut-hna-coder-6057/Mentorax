import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';

import '../../features/app_repository.dart';
import '../../shared/widgets/ui_components.dart';

class MentorMatch {
  const MentorMatch({
    required this.id,
    required this.name,
    required this.skills,
    required this.evidence,
    required this.relevanceEstimate,
    required this.uncertainty,
    required this.conversationTopic,
    required this.messageDraft,
    this.jobTitle,
    this.company,
  });

  final int id;
  final String name;
  final String? jobTitle;
  final String? company;
  final List<String> skills;
  final List<String> evidence;
  final double relevanceEstimate;
  final String uncertainty;
  final String conversationTopic;
  final String messageDraft;

  factory MentorMatch.fromJson(Map<String, dynamic> json) => MentorMatch(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String? ?? 'Mentorax alum',
        jobTitle: json['jobTitle'] as String?,
        company: json['company'] as String?,
        skills:
            (json['skills'] as List? ?? const []).whereType<String>().toList(),
        evidence: (json['evidence'] as List? ?? const [])
            .whereType<String>()
            .toList(),
        relevanceEstimate: (json['relevanceEstimate'] as num?)?.toDouble() ?? 0,
        uncertainty: json['uncertainty'] as String? ?? '',
        conversationTopic: json['conversationTopic'] as String? ?? '',
        messageDraft: json['messageDraft'] as String? ?? '',
      );
}

class MentorDiscoveryResult {
  const MentorDiscoveryResult(
      this.matches, this.rankingMethod, this.fallbackUsed);
  final List<MentorMatch> matches;
  final String rankingMethod;
  final bool fallbackUsed;

  factory MentorDiscoveryResult.fromJson(Map<String, dynamic> json) =>
      MentorDiscoveryResult(
        (json['recommendations'] as List? ?? const [])
            .map((item) =>
                MentorMatch.fromJson(Map<String, dynamic>.from(item as Map)))
            .toList(),
        json['rankingMethod'] as String? ?? 'Profile relevance ranking',
        json['fallbackUsed'] as bool? ?? true,
      );
}

final mentorDiscoveryProvider = StateNotifierProvider.autoDispose<
    MentorDiscoveryNotifier, AsyncValue<MentorDiscoveryResult?>>((ref) {
  return MentorDiscoveryNotifier(ref.watch(appRepositoryProvider));
});

class MentorDiscoveryNotifier
    extends StateNotifier<AsyncValue<MentorDiscoveryResult?>> {
  MentorDiscoveryNotifier(this._repository) : super(const AsyncData(null));
  final AppRepository _repository;
  bool _inFlight = false;
  String? _lastGoal;

  Future<void> discover(String goal, {bool retry = false}) async {
    if (_inFlight) return;
    final value = retry ? _lastGoal : goal.trim();
    if (value == null || value.isEmpty) return;
    _lastGoal = value;
    _inFlight = true;
    state = const AsyncLoading();
    try {
      state = AsyncData(MentorDiscoveryResult.fromJson(
          await _repository.discoverMentors(value)));
    } catch (error, stackTrace) {
      state = AsyncError(error, stackTrace);
    } finally {
      _inFlight = false;
    }
  }
}

class MentorDiscoveryScreen extends ConsumerStatefulWidget {
  const MentorDiscoveryScreen({super.key});

  @override
  ConsumerState<MentorDiscoveryScreen> createState() =>
      _MentorDiscoveryScreenState();
}

class _MentorDiscoveryScreenState extends ConsumerState<MentorDiscoveryScreen> {
  final _goal = TextEditingController();
  final Map<int, TextEditingController> _drafts = {};
  final Set<int> _requesting = {};

  static const _exampleQuestions = [
    'How should I prepare for software engineering internships?',
    'How can I improve my DSA and interview skills?',
    'What skills should I learn to become a machine learning engineer?',
  ];

  void _useExampleQuestion(String question) {
    setState(() {
      _goal.value = TextEditingValue(
        text: question,
        selection: TextSelection.collapsed(offset: question.length),
      );
    });
  }

  @override
  void dispose() {
    _goal.dispose();
    for (final controller in _drafts.values) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _requestConnection(MentorMatch match) async {
    if (!_requesting.add(match.id)) return;
    setState(() {});
    try {
      await ref.read(appRepositoryProvider).requestConnection(match.id);
      ref.invalidate(connectionsProvider);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('Connection request sent for your review.')),
      );
      context.push('/user/${match.id}');
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(userFacingError(error))),
        );
      }
    } finally {
      _requesting.remove(match.id);
      if (mounted) setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final result = ref.watch(mentorDiscoveryProvider);
    final width = MediaQuery.sizeOf(context).width;
    return Scaffold(
      appBar: AppBar(title: const Text('Find Relevant Alumni')),
      body: ListView(
        padding: EdgeInsets.all(width >= 900 ? 32 : 20),
        children: [
          Text('Ask a career question',
              style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: AppSpacing.sm),
          const Text(
              'Enter a career question to find approved alumni with relevant experience. Your question is only used to search profiles; it is not sent to any alumnus.'),
          const SizedBox(height: AppSpacing.md),
          TextField(
            controller: _goal,
            minLines: 2,
            maxLines: 5,
            maxLength: 500,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(
              labelText: 'What would you like to ask a mentor?',
              hintText: 'Enter a career-related question',
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: AppSpacing.xs),
          Text('Example questions',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: AppSpacing.xs),
          Wrap(
            spacing: AppSpacing.xs,
            runSpacing: AppSpacing.xs,
            children: [
              for (final question in _exampleQuestions)
                ActionChip(
                  label: Text(question),
                  onPressed: () => _useExampleQuestion(question),
                ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          result.isLoading
              ? const FilledButton(
                  onPressed: null,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2)),
                      SizedBox(width: 12),
                      Text('Finding relevant alumni…'),
                    ],
                  ),
                )
              : FilledButton.icon(
                  key: const ValueKey('findRelevantAlumniButton'),
                  onPressed: () {
                    if (_goal.text.trim().isNotEmpty) {
                      ref
                          .read(mentorDiscoveryProvider.notifier)
                          .discover(_goal.text);
                    }
                  },
                  icon: const Icon(Icons.auto_awesome),
                  label: const Text('Find Relevant Alumni'),
                ),
          const SizedBox(height: AppSpacing.lg),
          if (result.hasError)
            ErrorState(
              title: 'Mentor discovery could not finish',
              message: userFacingError(result.error!),
              onRetry: () => ref
                  .read(mentorDiscoveryProvider.notifier)
                  .discover(_goal.text, retry: true),
            ),
          if (result.valueOrNull case final data?) ...[
            if (data.matches.isEmpty)
              const EmptyState(
                title: 'No relevant alumni found',
                message:
                    'No approved alumni matched this question. Try another career question or refine your wording.',
                icon: Icons.person_search_outlined,
              )
            else ...[
              Text(data.fallbackUsed
                  ? 'Local fallback ranking · ${data.rankingMethod}'
                  : 'TF-IDF ranking · ${data.rankingMethod}'),
              const SizedBox(height: AppSpacing.sm),
              for (final match in data.matches) _mentorCard(context, match),
            ],
          ],
        ],
      ),
    );
  }

  Widget _mentorCard(BuildContext context, MentorMatch match) {
    final draft = _drafts.putIfAbsent(
        match.id, () => TextEditingController(text: match.messageDraft));
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: AppCard(
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(match.name, style: Theme.of(context).textTheme.titleLarge),
              if ([match.jobTitle, match.company]
                  .where((value) => value?.trim().isNotEmpty == true)
                  .isNotEmpty)
                Text([match.jobTitle, match.company]
                    .where((value) => value?.trim().isNotEmpty == true)
                    .join(' · ')),
              const SizedBox(height: AppSpacing.sm),
              Text(
                  'Relevance estimate: ${match.relevanceEstimate.toStringAsFixed(1)}%'),
              if (match.skills.isNotEmpty) ...[
                const SizedBox(height: AppSpacing.xs),
                Text('Profile skills: ${match.skills.join(', ')}'),
              ],
              const SizedBox(height: AppSpacing.sm),
              Text('Why this match',
                  style: Theme.of(context).textTheme.titleMedium),
              if (match.evidence.isEmpty)
                const Text('No direct matching profile evidence was found.')
              else
                for (final evidence in match.evidence)
                  Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.xs),
                    child: Text('• $evidence'),
                  ),
              const SizedBox(height: AppSpacing.sm),
              Text(match.uncertainty),
              const SizedBox(height: AppSpacing.xs),
              Text('Suggested topic: ${match.conversationTopic}'),
              const SizedBox(height: AppSpacing.md),
              TextField(
                controller: draft,
                minLines: 3,
                maxLines: 6,
                maxLength: 2000,
                textCapitalization: TextCapitalization.sentences,
                decoration: const InputDecoration(
                  labelText: 'Editable message draft',
                  alignLabelWithHint: true,
                ),
              ),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  OutlinedButton.icon(
                    onPressed: () async {
                      await Clipboard.setData(ClipboardData(text: draft.text));
                      if (context.mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                              content:
                                  Text('Draft copied. It has not been sent.')),
                        );
                      }
                    },
                    icon: const Icon(Icons.copy),
                    label: const Text('Copy draft'),
                  ),
                  FilledButton.tonalIcon(
                    onPressed: _requesting.contains(match.id)
                        ? null
                        : () => _requestConnection(match),
                    icon: _requesting.contains(match.id)
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2))
                        : const Icon(Icons.person_add_alt_1),
                    label: const Text('Request connection'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
