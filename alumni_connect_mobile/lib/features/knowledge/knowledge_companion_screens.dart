import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../app/providers.dart';
import '../app_repository.dart';

class KnowledgeCompanionScreen extends ConsumerStatefulWidget {
  const KnowledgeCompanionScreen({super.key});

  @override
  ConsumerState<KnowledgeCompanionScreen> createState() =>
      _KnowledgeCompanionScreenState();
}

class _KnowledgeCompanionScreenState
    extends ConsumerState<KnowledgeCompanionScreen> {
  final _question = TextEditingController();
  late AppRepository _repository;
  List<Map<String, dynamic>> _conversations = [];
  List<Map<String, dynamic>> _messages = [];
  int? _selectedId;
  bool _loading = true;
  bool _sending = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _repository = ref.read(appRepositoryProvider);
    _load();
  }

  @override
  void dispose() {
    _question.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final rows = await _repository.knowledgeConversations();
      final selected =
          _selectedId ??
          (rows.isEmpty ? null : (rows.first['id'] as num).toInt());
      final messages = selected == null
          ? <Map<String, dynamic>>[]
          : await _repository.knowledgeMessages(selected);
      if (!mounted) return;
      setState(() {
        _conversations = rows;
        _selectedId = selected;
        _messages = messages;
        _loading = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'Your knowledge conversations could not be loaded.';
        });
      }
    }
  }

  Future<void> _newConversation() async {
    try {
      final row = await _repository.createKnowledgeConversation();
      if (!mounted) return;
      setState(() {
        _selectedId = (row['id'] as num).toInt();
        _messages = [];
      });
      await _load();
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'A new conversation could not be started.');
      }
    }
  }

  Future<void> _select(Map<String, dynamic> row) async {
    final id = (row['id'] as num).toInt();
    setState(() {
      _selectedId = id;
      _loading = true;
      _error = null;
    });
    try {
      final messages = await _repository.knowledgeMessages(id);
      if (mounted) {
        setState(() {
          _messages = messages;
          _loading = false;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = 'This conversation could not be opened.';
        });
      }
    }
  }

  Future<void> _send() async {
    final text = _question.text.trim();
    if (text.isEmpty || _sending) return;
    try {
      var id = _selectedId;
      if (id == null) {
        final created = await _repository.createKnowledgeConversation();
        id = (created['id'] as num).toInt();
      }
      setState(() {
        _sending = true;
        _error = null;
      });
      final messages = await _repository.askKnowledgeQuestion(id, text);
      if (!mounted) return;
      _question.clear();
      setState(() {
        _selectedId = id;
        _messages = messages;
        _sending = false;
      });
      await _loadConversationsOnly();
    } catch (_) {
      if (mounted) {
        setState(() {
          _sending = false;
          _error = 'Your question could not be sent. Please retry.';
        });
      }
    }
  }

  Future<void> _loadConversationsOnly() async {
    try {
      final rows = await _repository.knowledgeConversations();
      if (mounted) setState(() => _conversations = rows);
    } catch (_) {
      /* The current answer remains available; retry is available from history. */
    }
  }

  Future<void> _deleteConversation() async {
    final id = _selectedId;
    if (id == null) return;
    try {
      await _repository.deleteKnowledgeConversation(id);
      if (!mounted) return;
      setState(() {
        _selectedId = null;
        _messages = [];
      });
      await _load();
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'The conversation could not be deleted.');
      }
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: const Text('Knowledge Companion'),
      actions: [
        IconButton(
          tooltip: 'Preparation roadmaps',
          onPressed: () => context.push('/student/knowledge-roadmaps'),
          icon: const Icon(Icons.route_outlined),
        ),
        IconButton(
          tooltip: 'New conversation',
          onPressed: _newConversation,
          icon: const Icon(Icons.add_comment_outlined),
        ),
        if (_selectedId != null)
          IconButton(
            tooltip: 'Delete conversation',
            onPressed: _deleteConversation,
            icon: const Icon(Icons.delete_outline),
          ),
      ],
    ),
    body: _loading && _conversations.isEmpty
        ? const Center(child: CircularProgressIndicator())
        : Column(
            children: [
              if (_conversations.isNotEmpty)
                SizedBox(
                  height: 62,
                  child: ListView.separated(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 12,
                      vertical: 8,
                    ),
                    scrollDirection: Axis.horizontal,
                    itemCount: _conversations.length,
                    separatorBuilder: (_, __) => const SizedBox(width: 8),
                    itemBuilder: (context, index) {
                      final row = _conversations[index];
                      final id = (row['id'] as num).toInt();
                      return ChoiceChip(
                        label: Text(row['title'] as String? ?? 'Conversation'),
                        selected: id == _selectedId,
                        onSelected: (_) => _select(row),
                      );
                    },
                  ),
                ),
              if (_error != null)
                MaterialBanner(
                  content: Text(_error!),
                  actions: [
                    TextButton(onPressed: _load, child: const Text('Retry')),
                  ],
                ),
              Expanded(
                child: _messages.isEmpty
                    ? const Center(
                        child: Padding(
                          padding: EdgeInsets.all(28),
                          child: Text(
                            'Ask about career preparation or practical alumni experience. Answers use currently eligible alumni contributions and show their sources.',
                          ),
                        ),
                      )
                    : ListView.builder(
                        padding: const EdgeInsets.all(16),
                        itemCount: _messages.length,
                        itemBuilder: (context, index) =>
                            _messageCard(context, _messages[index]),
                      ),
              ),
              SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
                  child: Row(
                    children: [
                      Expanded(
                        child: TextField(
                          key: const Key('knowledge-question'),
                          controller: _question,
                          maxLength: 500,
                          minLines: 1,
                          maxLines: 4,
                          decoration: const InputDecoration(
                            labelText: 'Ask a question',
                            border: OutlineInputBorder(),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      IconButton.filled(
                        tooltip: 'Send question',
                        onPressed: _sending ? null : _send,
                        icon: _sending
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
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

  Widget _messageCard(BuildContext context, Map<String, dynamic> message) {
    final assistant = message['speaker'] == 'ASSISTANT';
    final sources = (message['sources'] as List? ?? const [])
        .whereType<Map>()
        .toList();
    final mentors = (message['mentors'] as List? ?? const [])
        .whereType<Map>()
        .toList();
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      color: assistant
          ? Theme.of(context).colorScheme.surfaceContainerLow
          : Theme.of(context).colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              assistant ? 'Knowledge Companion' : 'You',
              style: Theme.of(context).textTheme.labelLarge,
            ),
            const SizedBox(height: 6),
            SelectableText(message['content'] as String? ?? ''),
            if (sources.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Text('Sources'),
              for (final source in sources)
                Text(
                  source['currentlyEligible'] == false
                      ? 'Contribution source is no longer eligible.'
                      : 'Contribution ${source['contributionId']} · version ${source['version']} · ${source['title'] ?? 'Alumni-reported'}',
                ),
            ],
            if (mentors.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Text(
                'A human follow-up may help. Choose a profile to review; no request is sent automatically.',
              ),
              for (final mentor in mentors)
                TextButton.icon(
                  onPressed: () => context.push('/user/${mentor['id']}'),
                  icon: const Icon(Icons.person_search_outlined),
                  label: Text(
                    '${mentor['displayName']} · ${mentor['role'] ?? 'Alumni'}',
                  ),
                ),
            ],
            if (message['answerMode'] == 'DETERMINISTIC_GROUNDED')
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'Answer mode: deterministic, source-grounded summary.',
                ),
              ),
            if (message['answerMode'] == 'OLLAMA_GROUNDED')
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'Answer mode: local model-assisted summary with source references.',
                ),
              ),
            if (message['answerMode'] == 'DETERMINISTIC_PROVIDER_FALLBACK' ||
                message['answerMode'] == 'DETERMINISTIC_PROVIDER_UNAVAILABLE')
              const Padding(
                padding: EdgeInsets.only(top: 8),
                child: Text(
                  'The answer provider was unavailable; a deterministic grounded response was used.',
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class KnowledgeRoadmapsScreen extends ConsumerStatefulWidget {
  const KnowledgeRoadmapsScreen({super.key});
  @override
  ConsumerState<KnowledgeRoadmapsScreen> createState() =>
      _KnowledgeRoadmapsScreenState();
}

class _KnowledgeRoadmapsScreenState
    extends ConsumerState<KnowledgeRoadmapsScreen> {
  final _goal = TextEditingController();
  late AppRepository _repository;
  List<Map<String, dynamic>> _roadmaps = [];
  bool _busy = false;
  String? _error;
  @override
  void initState() {
    super.initState();
    _repository = ref.read(appRepositoryProvider);
    _load();
  }

  @override
  void dispose() {
    _goal.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final rows = await _repository.knowledgeRoadmaps();
      if (mounted) {
        setState(() {
          _roadmaps = rows;
          _error = null;
        });
      }
    } catch (_) {
      if (mounted) setState(() => _error = 'Roadmaps could not be loaded.');
    }
  }

  Future<void> _create() async {
    if (_goal.text.trim().isEmpty || _busy) return;
    setState(() => _busy = true);
    try {
      await _repository.createKnowledgeRoadmap(_goal.text.trim());
      _goal.clear();
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = 'The roadmap could not be created.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _update(
    Map<String, dynamic> roadmap,
    Map<String, dynamic> item,
    String status,
  ) async {
    try {
      await _repository.updateRoadmapItem(
        (roadmap['id'] as num).toInt(),
        (item['id'] as num).toInt(),
        title: item['title'] as String,
        description: item['description'] as String,
        status: status,
      );
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = 'Progress could not be saved.');
    }
  }

  Future<void> _customize(
    Map<String, dynamic> roadmap,
    Map<String, dynamic> item,
  ) async {
    final title = TextEditingController(text: item['title'] as String? ?? '');
    final description = TextEditingController(
      text: item['description'] as String? ?? '',
    );
    final save = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Customize roadmap step'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: title,
              maxLength: 180,
              decoration: const InputDecoration(labelText: 'Step title'),
            ),
            TextField(
              controller: description,
              maxLength: 1000,
              minLines: 2,
              maxLines: 5,
              decoration: const InputDecoration(labelText: 'Description'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (save == true) {
      try {
        await _repository.updateRoadmapItem(
          (roadmap['id'] as num).toInt(),
          (item['id'] as num).toInt(),
          title: title.text,
          description: description.text,
          status: item['progressStatus'] as String? ?? 'NOT_STARTED',
        );
        await _load();
      } catch (_) {
        if (mounted) {
          setState(() => _error = 'Roadmap step could not be customized.');
        }
      }
    }
    title.dispose();
    description.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Preparation Roadmaps')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Create a practical plan from eligible alumni reports and clearly labeled general suggestions.',
        ),
        const SizedBox(height: 12),
        TextField(
          key: const Key('roadmap-goal'),
          controller: _goal,
          maxLength: 500,
          decoration: const InputDecoration(
            labelText: 'What are you preparing for?',
            border: OutlineInputBorder(),
          ),
        ),
        Align(
          alignment: Alignment.centerRight,
          child: FilledButton.icon(
            onPressed: _busy ? null : _create,
            icon: const Icon(Icons.add),
            label: const Text('Create roadmap'),
          ),
        ),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        if (_roadmaps.isEmpty)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Text('No roadmaps yet. Your plans will appear here.'),
          ),
        for (final roadmap in _roadmaps)
          Card(
            child: ExpansionTile(
              title: Text(roadmap['title'] as String? ?? 'Preparation roadmap'),
              subtitle: Text(
                (roadmap['evidenceStatus'] as String? ?? '').replaceAll(
                  '_',
                  ' ',
                ),
              ),
              children: [
                for (final raw in roadmap['items'] as List? ?? const [])
                  Builder(
                    builder: (context) {
                      final item = Map<String, dynamic>.from(raw as Map);
                      final sources = (item['sources'] as List? ?? const [])
                          .whereType<Map>()
                          .toList();
                      final evidenceLabel =
                          item['sourceKind'] == 'ALUMNI_REPORTED'
                          ? 'Alumni-reported guidance.'
                          : 'General suggestion.';
                      return ListTile(
                        title: Text(item['title'] as String? ?? ''),
                        subtitle: Text(
                          '$evidenceLabel ${item['phase'] ?? ''}\n${item['description'] ?? ''}${sources.isEmpty
                              ? ''
                              : sources.any((s) => s['currentlyEligible'] == true)
                              ? '\nUses a currently eligible alumni source.'
                              : '\nA source is no longer eligible.'}',
                        ),
                        isThreeLine: true,
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            IconButton(
                              tooltip: 'Customize step',
                              onPressed: () => _customize(roadmap, item),
                              icon: const Icon(Icons.edit_outlined),
                            ),
                            DropdownButton<String>(
                              value:
                                  item['progressStatus'] as String? ??
                                  'NOT_STARTED',
                              items: const [
                                DropdownMenuItem(
                                  value: 'NOT_STARTED',
                                  child: Text('To do'),
                                ),
                                DropdownMenuItem(
                                  value: 'IN_PROGRESS',
                                  child: Text('In progress'),
                                ),
                                DropdownMenuItem(
                                  value: 'COMPLETED',
                                  child: Text('Done'),
                                ),
                              ],
                              onChanged: (value) {
                                if (value != null) {
                                  _update(roadmap, item, value);
                                }
                              },
                            ),
                          ],
                        ),
                      );
                    },
                  ),
              ],
            ),
          ),
      ],
    ),
  );
}

class AlumniKnowledgeContributionsScreen extends ConsumerStatefulWidget {
  const AlumniKnowledgeContributionsScreen({super.key});
  @override
  ConsumerState<AlumniKnowledgeContributionsScreen> createState() =>
      _AlumniKnowledgeContributionsScreenState();
}

class _AlumniKnowledgeContributionsScreenState
    extends ConsumerState<AlumniKnowledgeContributionsScreen> {
  late AppRepository _repository;
  bool _busy = false;
  String? _error;
  List<Map<String, dynamic>> _contributions = [];
  List<Map<String, dynamic>> _opportunities = [];
  @override
  void initState() {
    super.initState();
    _repository = ref.read(appRepositoryProvider);
    _load();
  }

  Future<void> _load() async {
    try {
      final values = await Future.wait([
        _repository.alumniKnowledgeContributions(),
        _repository.knowledgeOpportunities(),
      ]);
      if (mounted) {
        setState(() {
          _contributions = values[0];
          _opportunities = values[1];
          _error = null;
        });
      }
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Knowledge contributions could not be loaded.');
      }
    }
  }

  Future<void> _create() async {
    if (_busy) return;
    final draft = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => const _ContributionEditorDialog(),
    );
    if (draft == null || !mounted) return;
    setState(() => _busy = true);
    try {
      await _repository.createAlumniKnowledgeContribution(
        category: draft['category'] as String,
        title: draft['title'] as String,
        narrative: draft['narrative'] as String,
        companyName: draft['companyName'] as String?,
        jobRole: draft['jobRole'] as String?,
        experienceYear: draft['experienceYear'] as int?,
        entries: (draft['entries'] as List).cast<Map<String, dynamic>>(),
      );
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = 'Your draft could not be saved.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _publish(Map<String, dynamic> row) async {
    var student = row['studentVisibilityConsent'] as bool? ?? false;
    var ai = row['aiRetrievalConsent'] as bool? ?? false;
    final consent = await showDialog<List<bool>>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: const Text('Publish this contribution?'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '“${row['title'] ?? 'Untitled'}” · version ${row['version']}\n\nA draft remains private until you confirm publication. Choose each consent scope for this version.',
              ),
              CheckboxListTile(
                value: student,
                onChanged: (v) => setDialogState(() => student = v ?? false),
                title: const Text('Student visibility'),
              ),
              CheckboxListTile(
                value: ai,
                onChanged: (v) => setDialogState(() => ai = v ?? false),
                title: const Text('AI retrieval'),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: student && ai
                  ? () => Navigator.pop(context, [student, ai])
                  : null,
              child: const Text('Publish'),
            ),
          ],
        ),
      ),
    );
    if (consent == null) return;
    try {
      await _repository.publishAlumniKnowledge(
        (row['id'] as num).toInt(),
        studentVisibilityConsent: consent[0],
        aiRetrievalConsent: consent[1],
      );
      await _load();
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'The contribution could not be published.');
      }
    }
  }

  Future<void> _withdraw(Map<String, dynamic> row) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Withdraw this contribution?'),
        content: Text(
          '“${row['title'] ?? 'Untitled'}” will immediately stop appearing to students and in AI retrieval. Its owner history and versions remain available.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Withdraw'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await _repository.withdrawAlumniKnowledge((row['id'] as num).toInt());
      await _load();
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'The contribution could not be withdrawn.');
      }
    }
  }

  Future<void> _edit(Map<String, dynamic> row) async {
    final data = await showDialog<Map<String, dynamic>>(
      context: context,
      builder: (_) => _ContributionEditorDialog(initial: row),
    );
    if (data != null) {
      try {
        await _repository.updateAlumniKnowledgeContribution(
          (row['id'] as num).toInt(),
          category: data['category'] as String,
          title: data['title'] as String,
          narrative: data['narrative'] as String,
          companyName: data['companyName'] as String?,
          jobRole: data['jobRole'] as String?,
          experienceYear: data['experienceYear'] as int?,
          entries: (data['entries'] as List).cast<Map<String, dynamic>>(),
        );
        await _load();
      } catch (_) {
        if (mounted) {
          setState(() => _error = 'A new version could not be saved.');
        }
      }
    }
  }

  Future<void> _freshness(Map<String, dynamic> row) async {
    final id = (row['id'] as num).toInt();
    final interval = await showDialog<int>(
      context: context,
      builder: (context) => SimpleDialog(
        title: const Text('Freshness reminder'),
        children: [
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 90),
            child: const Text('Every 90 days'),
          ),
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 180),
            child: const Text('Every 6 months'),
          ),
          SimpleDialogOption(
            onPressed: () => Navigator.pop(context, 0),
            child: const Text('Turn reminders off'),
          ),
        ],
      ),
    );
    if (interval == null) return;
    try {
      await _repository.setKnowledgeFreshness(
        id,
        intervalDays: interval == 90 ? 90 : 180,
        remindersEnabled: interval != 0,
      );
      if (interval != 0) await _repository.confirmKnowledgeFreshness(id);
      await _load();
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Freshness settings could not be saved.');
      }
    }
  }

  Future<void> _consent(Map<String, dynamic> row) async {
    var student = row['studentVisibilityConsent'] as bool? ?? false;
    var ai = row['aiRetrievalConsent'] as bool? ?? false;
    final values = await showDialog<List<bool>>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(
            'Consent · ${row['title'] ?? 'Contribution'} · v${row['version']}',
          ),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                'These scopes apply to version ${row['version']}. Saving consent does not publish a draft. Turning a scope off immediately suppresses it on the active publication. A grant on a newer draft never reactivates a scope revoked on an older version.',
              ),
              CheckboxListTile(
                value: student,
                onChanged: (value) =>
                    setDialogState(() => student = value ?? false),
                title: const Text('Student visibility'),
              ),
              CheckboxListTile(
                value: ai,
                onChanged: (value) => setDialogState(() => ai = value ?? false),
                title: const Text('AI retrieval'),
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, [student, ai]),
              child: const Text('Save consent'),
            ),
          ],
        ),
      ),
    );
    if (values == null) return;
    try {
      await _repository.setKnowledgeConsent(
        (row['id'] as num).toInt(),
        studentVisibilityConsent: values[0],
        aiRetrievalConsent: values[1],
      );
      await _load();
    } catch (_) {
      if (mounted) setState(() => _error = 'Consent could not be updated.');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Alumni Knowledge')),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Share practical experience. New content remains private until you publish with both consent scopes.',
        ),
        const SizedBox(height: 12),
        Align(
          alignment: Alignment.centerRight,
          child: FilledButton.icon(
            onPressed: _busy ? null : _create,
            icon: const Icon(Icons.save_outlined),
            label: const Text('Create structured draft'),
          ),
        ),
        if (_error != null)
          Text(
            _error!,
            style: TextStyle(color: Theme.of(context).colorScheme.error),
          ),
        const SizedBox(height: 24),
        Text(
          'Your contributions',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        if (_contributions.isEmpty) const Text('No contributions yet.'),
        for (final row in _contributions)
          Card(
            child: ListTile(
              title: Text(row['title'] as String? ?? ''),
              subtitle: Text(
                '${row['status']} · current v${row['version']} · published v${row['publishedVersion'] ?? '—'} · students ${row['studentVisibilityConsent'] == true ? 'on' : 'off'} · AI ${row['aiRetrievalConsent'] == true ? 'on' : 'off'}',
              ),
              isThreeLine: true,
              trailing: PopupMenuButton<String>(
                onSelected: (action) {
                  if (action == 'edit') _edit(row);
                  if (action == 'publish') _publish(row);
                  if (action == 'consent') _consent(row);
                  if (action == 'withdraw') _withdraw(row);
                  if (action == 'freshness') _freshness(row);
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(
                    value: 'edit',
                    child: Text('Edit as new version'),
                  ),
                  PopupMenuItem(
                    value: 'publish',
                    child: Text('Publish with selected consent'),
                  ),
                  PopupMenuItem(
                    value: 'consent',
                    child: Text('Change consent scopes'),
                  ),
                  PopupMenuItem(
                    value: 'freshness',
                    child: Text('Set freshness reminder'),
                  ),
                  PopupMenuItem(value: 'withdraw', child: Text('Withdraw')),
                ],
              ),
            ),
          ),
        const SizedBox(height: 24),
        Text(
          'Knowledge opportunities (aggregated)',
          style: Theme.of(context).textTheme.titleLarge,
        ),
        const Text(
          'Topics appear only after enough distinct students asked unanswered questions. Individual conversations are not shown.',
        ),
        if (_opportunities.isEmpty)
          const ListTile(
            title: Text('No topic has reached the privacy threshold yet.'),
          ),
        for (final topic in _opportunities)
          ListTile(
            leading: const Icon(Icons.lightbulb_outline),
            title: Text(topic['topic'] as String? ?? ''),
            subtitle: Text('${topic['distinctStudents']} distinct students'),
          ),
      ],
    ),
  );
}

class _ContributionEditorDialog extends StatefulWidget {
  const _ContributionEditorDialog({this.initial});
  final Map<String, dynamic>? initial;

  @override
  State<_ContributionEditorDialog> createState() =>
      _ContributionEditorDialogState();
}

class _ContributionEditorDialogState extends State<_ContributionEditorDialog> {
  static const _categories = [
    'INTERVIEW_EXPERIENCE',
    'PREPARATION_STRATEGY',
    'PROJECT_EXPERIENCE',
    'CAREER_JOURNEY',
    'PROFESSIONAL_ADVICE',
    'MENTORSHIP_GUIDANCE',
  ];
  static const _entryTypes = [
    'TECHNOLOGY',
    'INTERVIEW_ROUND',
    'INTERVIEW_TOPIC',
    'INTERVIEW_QUESTION',
    'RESOURCE',
    'CHALLENGE',
    'LESSON',
    'ROLE_TRANSITION',
  ];
  final _formKey = GlobalKey<FormState>();
  late final _title = TextEditingController(
    text: widget.initial?['title'] as String? ?? '',
  );
  late final _narrative = TextEditingController(
    text: widget.initial?['narrative'] as String? ?? '',
  );
  late final _company = TextEditingController(
    text: widget.initial?['companyName'] as String? ?? '',
  );
  late final _role = TextEditingController(
    text: widget.initial?['jobRole'] as String? ?? '',
  );
  late final _year = TextEditingController(
    text: widget.initial?['experienceYear']?.toString() ?? '',
  );
  late String _category =
      widget.initial?['category'] as String? ?? _categories.first;
  late final List<_KnowledgeEntryDraft> _entries =
      (widget.initial?['entries'] as List? ?? const [])
          .whereType<Map>()
          .map(
            (entry) =>
                _KnowledgeEntryDraft.fromMap(Map<String, dynamic>.from(entry)),
          )
          .toList();

  @override
  void dispose() {
    _title.dispose();
    _narrative.dispose();
    _company.dispose();
    _role.dispose();
    _year.dispose();
    for (final entry in _entries) {
      entry.dispose();
    }
    super.dispose();
  }

  String get _guidance => switch (_category) {
    'INTERVIEW_EXPERIENCE' =>
      'Which company and role? What rounds, topics, questions, technologies, challenges, and preparation helped?',
    'PREPARATION_STRATEGY' =>
      'What role were you preparing for? Which topics, skills, and resources helped? What strategy, mistakes, and lessons would you share?',
    'PROJECT_EXPERIENCE' =>
      'What problem did the project solve? Describe your contribution, technologies, approach, challenges, resolution, and lessons.',
    'CAREER_JOURNEY' =>
      'Describe the move from your previous role to your new role, when it happened, the skills you learned, and useful resources and lessons.',
    'PROFESSIONAL_ADVICE' =>
      'Who is this for? What challenge are you addressing? Share practical recommendations, resources, and lessons.',
    _ =>
      'Describe your mentoring context, intended audience, common challenges, practical guidance, and lessons from experience.',
  };

  String? _required(String? value) =>
      value == null || value.trim().isEmpty ? 'Required' : null;
  void _save() {
    if (!_formKey.currentState!.validate()) return;
    Navigator.pop(context, <String, dynamic>{
      'category': _category,
      'title': _title.text.trim(),
      'narrative': _narrative.text.trim(),
      'companyName': _company.text.trim().isEmpty ? null : _company.text.trim(),
      'jobRole': _role.text.trim().isEmpty ? null : _role.text.trim(),
      'experienceYear': _year.text.trim().isEmpty
          ? null
          : int.parse(_year.text.trim()),
      'entries': _entries
          .where((entry) => entry.hasContent)
          .map((entry) => entry.toMap())
          .toList(),
    });
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(
      widget.initial == null
          ? 'New knowledge contribution'
          : 'Create a new version',
    ),
    content: SizedBox(
      width: 560,
      child: Form(
        key: _formKey,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              DropdownButtonFormField<String>(
                key: const Key('contribution-category'),
                initialValue: _category,
                decoration: const InputDecoration(labelText: 'Category'),
                items: [
                  for (final category in _categories)
                    DropdownMenuItem(
                      value: category,
                      child: Text(category.replaceAll('_', ' ')),
                    ),
                ],
                onChanged: (value) {
                  if (value != null) setState(() => _category = value);
                },
              ),
              TextFormField(
                key: const Key('contribution-title'),
                controller: _title,
                maxLength: 160,
                validator: _required,
                decoration: const InputDecoration(labelText: 'Title'),
              ),
              TextFormField(
                key: const Key('contribution-company'),
                controller: _company,
                maxLength: 150,
                decoration: const InputDecoration(
                  labelText: 'Company or organization (optional)',
                ),
              ),
              TextFormField(
                key: const Key('contribution-role'),
                controller: _role,
                maxLength: 150,
                decoration: const InputDecoration(
                  labelText: 'Job role or career goal (optional)',
                ),
              ),
              TextFormField(
                key: const Key('contribution-year'),
                controller: _year,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(
                  labelText: 'Experience year (optional)',
                ),
                validator: (value) {
                  if (value == null || value.trim().isEmpty) return null;
                  final n = int.tryParse(value);
                  return n == null || n < 1950 || n > 2100
                      ? 'Enter a year from 1950 to 2100'
                      : null;
                },
              ),
              TextFormField(
                key: const Key('contribution-narrative'),
                controller: _narrative,
                maxLength: 6000,
                minLines: 3,
                maxLines: 6,
                validator: _required,
                decoration: const InputDecoration(
                  labelText: 'Experience and practical context',
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Text(
                  _guidance,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      'Structured answers',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                  ),
                  TextButton.icon(
                    onPressed: () => setState(
                      () => _entries.add(
                        _KnowledgeEntryDraft(position: _entries.length),
                      ),
                    ),
                    icon: const Icon(Icons.add),
                    label: const Text('Add answer'),
                  ),
                ],
              ),
              if (_entries.isEmpty)
                const Text(
                  'Add technologies, rounds, topics, questions, resources, challenges, lessons, or role transitions as relevant.',
                ),
              for (var i = 0; i < _entries.length; i++) _entryCard(i),
            ],
          ),
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('Cancel'),
      ),
      FilledButton(
        onPressed: _save,
        child: Text(
          widget.initial == null
              ? 'Save private draft'
              : 'Save new draft version',
        ),
      ),
    ],
  );

  Widget _entryCard(int index) {
    final entry = _entries[index];
    return Card(
      key: ValueKey(entry),
      child: Padding(
        padding: const EdgeInsets.all(10),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: DropdownButtonFormField<String>(
                    initialValue: entry.type,
                    decoration: const InputDecoration(labelText: 'Answer type'),
                    items: [
                      for (final type in _entryTypes)
                        DropdownMenuItem(
                          value: type,
                          child: Text(type.replaceAll('_', ' ')),
                        ),
                    ],
                    onChanged: (value) {
                      if (value != null) setState(() => entry.type = value);
                    },
                  ),
                ),
                IconButton(
                  tooltip: 'Remove answer',
                  onPressed: () => setState(() {
                    _entries.removeAt(index).dispose();
                  }),
                  icon: const Icon(Icons.delete_outline),
                ),
              ],
            ),
            TextFormField(
              controller: entry.value,
              maxLength: 1000,
              decoration: const InputDecoration(
                labelText: 'Answer / resource / role detail',
              ),
            ),
            TextFormField(
              controller: entry.detail,
              maxLength: 2000,
              minLines: 1,
              maxLines: 3,
              decoration: const InputDecoration(
                labelText: 'Additional detail (optional)',
              ),
            ),
            TextFormField(
              controller: entry.url,
              maxLength: 500,
              keyboardType: TextInputType.url,
              decoration: const InputDecoration(
                labelText: 'HTTPS resource URL (optional)',
              ),
              validator: (value) =>
                  value == null ||
                      value.trim().isEmpty ||
                      value.trim().startsWith('https://')
                  ? null
                  : 'Use an HTTPS URL',
            ),
            TextFormField(
              controller: entry.year,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(
                labelText: 'Related year (optional)',
              ),
              validator: (value) {
                if (value == null || value.trim().isEmpty) return null;
                final n = int.tryParse(value);
                return n == null || n < 1950 || n > 2100
                    ? 'Enter a year from 1950 to 2100'
                    : null;
              },
            ),
            if (entry.type == 'ROLE_TRANSITION')
              Row(
                children: [
                  Expanded(
                    child: TextFormField(
                      controller: entry.fromRole,
                      decoration: const InputDecoration(
                        labelText: 'Previous role',
                      ),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: TextFormField(
                      controller: entry.toRole,
                      decoration: const InputDecoration(labelText: 'New role'),
                    ),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}

class _KnowledgeEntryDraft {
  _KnowledgeEntryDraft({
    this.type = 'TECHNOLOGY',
    this.position = 0,
    Map<String, dynamic>? source,
  }) : value = TextEditingController(text: source?['value'] as String? ?? ''),
       detail = TextEditingController(text: source?['detail'] as String? ?? ''),
       url = TextEditingController(text: source?['url'] as String? ?? ''),
       fromRole = TextEditingController(
         text: source?['fromRole'] as String? ?? '',
       ),
       toRole = TextEditingController(text: source?['toRole'] as String? ?? ''),
       year = TextEditingController(text: source?['year']?.toString() ?? ''),
       parentPosition = (source?['parentPosition'] as num?)?.toInt();
  factory _KnowledgeEntryDraft.fromMap(Map<String, dynamic> source) =>
      _KnowledgeEntryDraft(
        type: source['type'] as String? ?? 'TECHNOLOGY',
        position: (source['position'] as num?)?.toInt() ?? 0,
        source: source,
      );
  String type;
  final TextEditingController value, detail, url, fromRole, toRole, year;
  final int? parentPosition;
  int position;
  bool get hasContent => [
    value,
    detail,
    url,
    fromRole,
    toRole,
    year,
  ].any((c) => c.text.trim().isNotEmpty);
  Map<String, dynamic> toMap() => {
    'type': type,
    'position': position,
    'parentPosition': parentPosition,
    'value': value.text.trim().isEmpty ? null : value.text.trim(),
    'detail': detail.text.trim().isEmpty ? null : detail.text.trim(),
    'url': url.text.trim().isEmpty ? null : url.text.trim(),
    'fromRole': fromRole.text.trim().isEmpty ? null : fromRole.text.trim(),
    'toRole': toRole.text.trim().isEmpty ? null : toRole.text.trim(),
    'year': year.text.trim().isEmpty ? null : int.parse(year.text.trim()),
  };
  void dispose() {
    value.dispose();
    detail.dispose();
    url.dispose();
    fromRole.dispose();
    toRole.dispose();
    year.dispose();
  }
}
