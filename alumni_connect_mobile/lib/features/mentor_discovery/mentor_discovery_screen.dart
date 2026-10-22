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
  const MentorDiscoveryResult({
    required this.matches,
    required this.rankingMethod,
    required this.fallbackUsed,
    required this.intent,
    this.answer,
    this.needsClarification = false,
    this.clarificationQuestion,
    this.message,
    this.sources = const [],
  });
  final List<MentorMatch> matches;
  final String rankingMethod;
  final bool fallbackUsed;
  final String intent;
  final String? answer;
  final bool needsClarification;
  final String? clarificationQuestion;
  final String? message;
  final List<String> sources;

  factory MentorDiscoveryResult.fromJson(Map<String, dynamic> json) =>
      MentorDiscoveryResult(
        matches: (json['recommendations'] as List? ?? const [])
            .map((item) =>
                MentorMatch.fromJson(Map<String, dynamic>.from(item as Map)))
            .toList(),
        rankingMethod:
            json['rankingMethod'] as String? ?? 'Profile relevance ranking',
        fallbackUsed: json['fallbackUsed'] as bool? ?? true,
        intent: json['intent'] as String? ?? 'CAREER_MENTOR_SEARCH',
        answer: json['answer'] as String?,
        needsClarification: json['needsClarification'] as bool? ?? false,
        clarificationQuestion: json['clarificationQuestion'] as String?,
        message: json['message'] as String?,
        sources:
            (json['sources'] as List? ?? const []).whereType<String>().toList(),
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
  static const _generalExamples = [
    'Who is the Prime Minister of India?',
    'What is 2 + 2?',
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
      appBar: AppBar(title: const Text('Career Assistant')),
      body: ListView(
        padding: EdgeInsets.all(width >= 900 ? 32 : 20),
        children: [
          Text('What can I help you with?',
              style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: AppSpacing.sm),
          const Text(
              'Career questions find approved alumni. General questions are routed separately, but reliable factual answering is not configured yet. Nothing is sent to an alumnus automatically.'),
          const SizedBox(height: AppSpacing.md),
          TextField(
            controller: _goal,
            minLines: 2,
            maxLines: 5,
            maxLength: 500,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(
              labelText: 'Ask a career or general question',
              hintText: 'Type your question here',
              alignLabelWithHint: true,
            ),
          ),
          const SizedBox(height: AppSpacing.xs),
          Text('Career and mentor examples',
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
          Text('General question examples',
              style: Theme.of(context).textTheme.titleSmall),
          const SizedBox(height: AppSpacing.xs),
          Wrap(
            spacing: AppSpacing.xs,
            runSpacing: AppSpacing.xs,
            children: [
              for (final question in _generalExamples)
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
                      Text('Routing your question...'),
                    ],
                  ),
                )
              : FilledButton.icon(
                  key: const ValueKey('submitAssistantQuestion'),
                  onPressed: () {
                    if (_goal.text.trim().isNotEmpty) {
                      ref
                          .read(mentorDiscoveryProvider.notifier)
                          .discover(_goal.text);
                    }
                  },
                  icon: const Icon(Icons.auto_awesome),
                  label: const Text('Ask Career Assistant'),
                ),
          const SizedBox(height: AppSpacing.lg),
          if (result.hasError)
            ErrorState(
              title: 'Career assistant could not finish',
              message: userFacingError(result.error!),
              onRetry: () => ref
                  .read(mentorDiscoveryProvider.notifier)
                  .discover(_goal.text, retry: true),
            ),
          if (result.valueOrNull case final data?)
            _assistantResult(context, data),
        ],
      ),
    );
  }

  Widget _assistantResult(BuildContext context, MentorDiscoveryResult result) {
    if (result.intent == 'CAREER_MENTOR_SEARCH' ||
        result.intent == 'ALUMNI_QUESTION') {
      if (result.matches.isEmpty) {
        final noApprovedAlumni =
            result.rankingMethod == 'No approved alumni available';
        return EmptyState(
          title: noApprovedAlumni
              ? 'No approved alumni available'
              : 'No relevant alumni found',
          message: result.message ??
              'Try another career question or refine your wording.',
          icon: Icons.person_search_outlined,
        );
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (result.intent == 'ALUMNI_QUESTION' && result.message != null)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.sm),
              child: AppCard(
                child: Padding(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  child: Text(result.message!),
                ),
              ),
            ),
          Text(result.fallbackUsed
              ? 'Local fallback · ${result.rankingMethod}'
              : 'TF-IDF ordering · ${result.rankingMethod}'),
          const SizedBox(height: AppSpacing.sm),
          for (final match in result.matches) _mentorCard(context, match),
        ],
      );
    }

    final title = switch (result.intent) {
      'GENERAL_KNOWLEDGE' => 'General question',
      'GREETING_OR_SMALLTALK' => 'Hello',
      _ => result.needsClarification
          ? 'Could you clarify?'
          : 'Question not supported',
    };
    return AppCard(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            if (result.answer?.isNotEmpty == true) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(result.answer!),
            ],
            if (result.clarificationQuestion?.isNotEmpty == true) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(result.clarificationQuestion!),
            ],
            if (result.message?.isNotEmpty == true) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(result.message!),
            ],
            if (result.sources.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.sm),
              for (final source in result.sources) Text('Source: $source'),
            ],
          ],
        ),
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
                  'Profile keyword overlap (estimate, not probability): ${match.relevanceEstimate.toStringAsFixed(1)}%'),
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
