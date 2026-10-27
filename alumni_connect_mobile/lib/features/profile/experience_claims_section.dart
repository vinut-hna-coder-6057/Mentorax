import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:pdfrx/pdfrx.dart';

import '../../app/providers.dart';
import '../../shared/models/models.dart';
import '../../shared/widgets/ui_components.dart';

const _maxEvidenceBytes = 5 * 1024 * 1024;

class ExperienceClaimsSection extends ConsumerStatefulWidget {
  const ExperienceClaimsSection({super.key});

  @override
  ConsumerState<ExperienceClaimsSection> createState() =>
      _ExperienceClaimsSectionState();
}

class _ExperienceClaimsSectionState
    extends ConsumerState<ExperienceClaimsSection> {
  List<ExperienceClaim> _claims = const [];
  final Map<int, PlatformFile> _evidencePendingRetry = {};
  bool _loading = true;
  bool _saving = false;
  int? _uploadingClaimId;
  double? _uploadProgress;

  @override
  void initState() {
    super.initState();
    _loadClaims();
  }

  Future<void> _loadClaims() async {
    try {
      final claims = await ref.read(appRepositoryProvider).experienceClaims();
      if (!mounted) return;
      setState(() {
        _claims = claims;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _loading = false);
      _showMessage(
          'Could not load experience claims: ${userFacingError(error)}');
    }
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _createClaim() async {
    final draft = await showDialog<_ClaimDraft>(
      context: context,
      builder: (_) => const _ClaimEditorDialog(),
    );
    if (draft == null || !mounted) return;
    setState(() => _saving = true);
    try {
      final claim = await ref.read(appRepositoryProvider).createExperienceClaim(
            company: draft.company,
            jobTitle: draft.jobTitle,
            requestId: _newRequestId(),
            startMonth: draft.startMonth,
            endMonth: draft.endMonth,
            summary: draft.summary,
          );
      if (!mounted) return;
      setState(() => _claims = [claim, ..._claims]);
      if (draft.evidence != null) {
        await _uploadEvidence(claim.id, draft.evidence!);
      } else {
        _showMessage('Experience claim submitted for review.');
      }
    } catch (error) {
      if (mounted) {
        _showMessage('Could not submit claim: ${userFacingError(error)}');
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _editClaim(ExperienceClaim claim) async {
    final draft = await showDialog<_ClaimDraft>(
      context: context,
      builder: (_) => _ClaimEditorDialog(claim: claim),
    );
    if (draft == null || !mounted) return;
    setState(() => _saving = true);
    try {
      final updated =
          await ref.read(appRepositoryProvider).updateExperienceClaim(
                claim.id,
                company: draft.company,
                jobTitle: draft.jobTitle,
                requestId: claim.requestId ?? _newRequestId(),
                startMonth: draft.startMonth,
                endMonth: draft.endMonth,
                summary: draft.summary,
              );
      if (!mounted) return;
      _replaceClaim(updated);
      if (draft.evidence != null) {
        await _uploadEvidence(updated.id, draft.evidence!);
      } else {
        _showMessage('Claim updated and resubmitted for review.');
      }
    } catch (error) {
      if (mounted)
        _showMessage('Could not update claim: ${userFacingError(error)}');
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _chooseEvidence(int claimId) async {
    final picked = await _pickEvidence();
    if (picked == null || !mounted) return;
    final reviewed = await _showPdfPreview(
      picked.bytes!,
      picked.name,
      requirePrivacyConfirmation: true,
    );
    if (reviewed == true && mounted) {
      await _uploadEvidence(claimId, picked);
    }
  }

  Future<PlatformFile?> _pickEvidence() async {
    try {
      final result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['pdf'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) return null;
      final file = result.files.single;
      final bytes = file.bytes;
      if (!file.name.toLowerCase().endsWith('.pdf') ||
          file.size <= 0 ||
          file.size > _maxEvidenceBytes ||
          bytes == null ||
          bytes.length < 5 ||
          String.fromCharCodes(bytes.take(5)) != '%PDF-') {
        _showMessage('Select a valid PDF no larger than 5 MiB.');
        return null;
      }
      return file;
    } catch (error) {
      if (mounted)
        _showMessage('Could not select evidence: ${userFacingError(error)}');
      return null;
    }
  }

  Future<void> _uploadEvidence(int claimId, PlatformFile file) async {
    final bytes = file.bytes;
    if (bytes == null || bytes.isEmpty) {
      _showMessage(
          'The selected evidence is no longer available. Select it again.');
      return;
    }
    _evidencePendingRetry[claimId] = file;
    setState(() {
      _uploadingClaimId = claimId;
      _uploadProgress = 0;
    });
    try {
      final updated =
          await ref.read(appRepositoryProvider).uploadExperienceEvidence(
        claimId,
        file.name,
        bytes,
        onSendProgress: (sent, total) {
          if (!mounted || total <= 0) return;
          setState(() => _uploadProgress = sent / total);
        },
      );
      if (!mounted) return;
      setState(() {
        _evidencePendingRetry.remove(claimId);
        _replaceClaim(updated);
      });
      _showMessage('Evidence uploaded. Claim status: ${updated.status}.');
    } catch (error) {
      if (mounted) {
        _showMessage(
          'Evidence upload failed. Your claim is saved; retry when ready: ${userFacingError(error)}',
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _uploadingClaimId = null;
          _uploadProgress = null;
        });
      }
    }
  }

  Future<void> _retryEvidence(int claimId) async {
    final file = _evidencePendingRetry[claimId];
    if (file == null) {
      await _chooseEvidence(claimId);
      return;
    }
    await _uploadEvidence(claimId, file);
  }

  Future<void> _deleteEvidence(ExperienceClaim claim) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete private evidence?'),
        content: const Text(
          'The uploaded document and its analysis results will be removed. '
          'The claim and its audit history will remain.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Delete evidence'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    try {
      await ref.read(appRepositoryProvider).deleteExperienceEvidence(claim.id);
      if (!mounted) return;
      setState(() => _replaceClaim(_withoutEvidence(claim)));
      _showMessage('Private evidence deleted.');
    } catch (error) {
      if (mounted)
        _showMessage('Could not delete evidence: ${userFacingError(error)}');
    }
  }

  Future<void> _viewEvidence(ExperienceClaim claim) async {
    try {
      final bytes =
          await ref.read(appRepositoryProvider).experienceEvidence(claim.id);
      if (!mounted) return;
      await _showPdfPreview(
        bytes,
        claim.evidenceFileName ?? 'private-evidence.pdf',
        requirePrivacyConfirmation: false,
      );
    } catch (error) {
      if (mounted)
        _showMessage(
            'Could not open private evidence: ${userFacingError(error)}');
    }
  }

  Future<void> _showHistory(ExperienceClaim claim) async {
    try {
      final history = await ref
          .read(appRepositoryProvider)
          .experienceClaimHistory(claim.id);
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Claim history'),
          content: SizedBox(
            width: 480,
            child: history.isEmpty
                ? const Text('No history is available.')
                : ListView(
                    shrinkWrap: true,
                    children: [
                      for (final event in history)
                        ListTile(
                          contentPadding: EdgeInsets.zero,
                          title: Text(_eventTitle(event)),
                          subtitle: Text([
                            if (event.oldStatus != null ||
                                event.newStatus != null)
                              '${event.oldStatus ?? '—'} → ${event.newStatus ?? '—'}',
                            if (event.eventNote?.isNotEmpty == true)
                              event.eventNote!,
                            event.createdAt,
                          ].join('\n')),
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
      if (mounted)
        _showMessage('Could not load claim history: ${userFacingError(error)}');
    }
  }

  Future<void> _appealClaim(ExperienceClaim claim) async {
    final note = await _showNoteDialog(
      title: 'Appeal this decision',
      label: 'Explain what should be reconsidered',
      action: 'Submit appeal',
    );
    if (note == null || !mounted) return;
    try {
      final updated =
          await ref.read(appRepositoryProvider).appealExperienceClaim(
                claim.id,
                note,
              );
      if (!mounted) return;
      setState(() => _replaceClaim(updated));
      _showMessage('Appeal submitted for review.');
    } catch (error) {
      if (mounted)
        _showMessage('Could not submit appeal: ${userFacingError(error)}');
    }
  }

  Future<String?> _showNoteDialog({
    required String title,
    required String label,
    required String action,
  }) async {
    final controller = TextEditingController();
    try {
      return await showDialog<String>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(title),
          content: TextField(
            controller: controller,
            autofocus: true,
            maxLines: 4,
            maxLength: 1000,
            decoration: InputDecoration(labelText: label),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Cancel'),
            ),
            FilledButton(
              onPressed: () {
                if (controller.text.trim().isNotEmpty) {
                  Navigator.pop(context, controller.text.trim());
                }
              },
              child: Text(action),
            ),
          ],
        ),
      );
    } finally {
      controller.dispose();
    }
  }

  Future<bool?> _showPdfPreview(
    Uint8List bytes,
    String filename, {
    required bool requirePrivacyConfirmation,
  }) async {
    if (!_hasPdfSignature(bytes)) {
      _showMessage('This document is not a valid PDF.');
      return false;
    }
    var privacyReviewed = !requirePrivacyConfirmation;
    return showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title:
              Text(requirePrivacyConfirmation ? 'Review evidence' : filename),
          content: SizedBox(
            width: 640,
            height: 560,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: PdfViewer.data(
                      bytes,
                      sourceName: '$filename-${bytes.length}',
                    ),
                  ),
                ),
                const SizedBox(height: AppSpacing.sm),
                Text(
                  'Private document · ${_formatBytes(bytes.length)}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
                if (requirePrivacyConfirmation) ...[
                  const SizedBox(height: AppSpacing.xs),
                  const Text(
                    'Only upload evidence needed to support this claim. '
                    'Permanently redact account numbers, government IDs, '
                    'home addresses, and unrelated people’s information in a '
                    'trusted PDF editor before uploading. Redaction cannot be '
                    'performed inside Mentorax.',
                  ),
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    value: privacyReviewed,
                    onChanged: (value) => setDialogState(
                      () => privacyReviewed = value ?? false,
                    ),
                    title: const Text(
                      'I reviewed this document and removed unnecessary sensitive information.',
                    ),
                  ),
                ],
              ],
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(requirePrivacyConfirmation ? 'Cancel' : 'Close'),
            ),
            if (requirePrivacyConfirmation)
              FilledButton(
                onPressed:
                    privacyReviewed ? () => Navigator.pop(context, true) : null,
                child: const Text('Use this evidence'),
              ),
          ],
        ),
      ),
    );
  }

  void _replaceClaim(ExperienceClaim updated) {
    final index = _claims.indexWhere((item) => item.id == updated.id);
    if (index < 0) {
      _claims = [updated, ..._claims];
    } else {
      _claims[index] = updated;
    }
  }

  ExperienceClaim _withoutEvidence(ExperienceClaim claim) => ExperienceClaim(
        id: claim.id,
        company: claim.company,
        jobTitle: claim.jobTitle,
        requestId: claim.requestId,
        summary: claim.summary,
        status: claim.status,
        aiStatus: 'PENDING',
        evidenceAvailable: false,
        startMonth: claim.startMonth,
        endMonth: claim.endMonth,
        evidenceSummary: claim.evidenceSummary,
        appealNote: claim.appealNote,
        createdAt: claim.createdAt,
        updatedAt: claim.updatedAt,
      );

  @override
  Widget build(BuildContext context) => AppCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(
                    child: AppSectionTitle('Experience verification')),
                IconButton(
                  tooltip: 'Refresh claims',
                  onPressed: _loading ? null : _loadClaims,
                  icon: const Icon(Icons.refresh),
                ),
              ],
            ),
            const Text(
              'Claims remain unverified until an authorized review decides them. '
              'Document analysis is only a consistency aid.',
            ),
            const SizedBox(height: AppSpacing.md),
            if (_loading)
              const Center(child: CircularProgressIndicator())
            else if (_claims.isEmpty)
              const Text('No experience claims have been submitted.')
            else
              for (final claim in _claims) _claimCard(claim),
            const SizedBox(height: AppSpacing.sm),
            FilledButton.icon(
              onPressed: _saving ? null : _createClaim,
              icon: const Icon(Icons.add),
              label: Text(_saving ? 'Saving claim…' : 'Add experience claim'),
            ),
          ],
        ),
      );

  Widget _claimCard(ExperienceClaim claim) {
    final isUploading = _uploadingClaimId == claim.id;
    final canEdit = claim.status.toUpperCase() == 'NEEDS_INFO';
    final isRejected = claim.status.toUpperCase() == 'REJECTED';
    return Card(
      margin: const EdgeInsets.only(top: AppSpacing.md),
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    '${claim.jobTitle} · ${claim.company}',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                StatusBadge(claim.status),
              ],
            ),
            if (claim.startMonth != null || claim.endMonth != null)
              Padding(
                padding: const EdgeInsets.only(top: AppSpacing.xs),
                child: Text(
                  '${claim.startMonth ?? '—'} – ${claim.endMonth ?? 'Present'}',
                ),
              ),
            const SizedBox(height: AppSpacing.xs),
            Text(claim.summary),
            const SizedBox(height: AppSpacing.sm),
            Text(
              'Document analysis: ${claim.aiStatus}. This does not verify employment.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (claim.aiFindings?.isNotEmpty == true)
              _findings(claim.aiFindings!),
            if (isUploading) ...[
              const SizedBox(height: AppSpacing.sm),
              LinearProgressIndicator(value: _uploadProgress),
              if (_uploadProgress != null)
                Text('${(_uploadProgress! * 100).round()}% uploaded'),
            ],
            if (claim.evidenceAvailable) ...[
              const SizedBox(height: AppSpacing.sm),
              Text('Private evidence: ${claim.evidenceFileName ?? 'PDF'}'),
            ],
            const SizedBox(height: AppSpacing.sm),
            Wrap(
              spacing: AppSpacing.xs,
              runSpacing: AppSpacing.xs,
              children: [
                if (claim.evidenceAvailable)
                  OutlinedButton.icon(
                    onPressed: isUploading ? null : () => _viewEvidence(claim),
                    icon: const Icon(Icons.visibility_outlined),
                    label: const Text('Preview evidence'),
                  ),
                if (claim.evidenceAvailable)
                  TextButton(
                    onPressed:
                        isUploading ? null : () => _deleteEvidence(claim),
                    child: const Text('Delete evidence'),
                  ),
                if (!claim.evidenceAvailable &&
                    (claim.status == 'PENDING' || canEdit))
                  OutlinedButton.icon(
                    onPressed:
                        isUploading ? null : () => _retryEvidence(claim.id),
                    icon: Icon(_evidencePendingRetry.containsKey(claim.id)
                        ? Icons.refresh
                        : Icons.attach_file),
                    label: Text(_evidencePendingRetry.containsKey(claim.id)
                        ? 'Retry evidence upload'
                        : 'Attach evidence'),
                  ),
                if (canEdit)
                  OutlinedButton(
                    onPressed: _saving ? null : () => _editClaim(claim),
                    child: const Text('Update requested information'),
                  ),
                if (isRejected)
                  OutlinedButton(
                    onPressed: () => _appealClaim(claim),
                    child: const Text('Appeal'),
                  ),
                TextButton(
                  onPressed: () => _showHistory(claim),
                  child: const Text('History'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _findings(String raw) {
    dynamic findings;
    try {
      findings = jsonDecode(raw);
    } on FormatException {
      return const Text('Analysis details are unavailable.');
    }
    if (findings is! List || findings.isEmpty) {
      return const SizedBox.shrink();
    }
    return ExpansionTile(
      tilePadding: EdgeInsets.zero,
      title: const Text('Analysis details'),
      children: [
        for (final item in findings)
          if (item is Map)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                '${item['field'] ?? 'Finding'} · ${item['outcome'] ?? 'INCONCLUSIVE'}',
              ),
              subtitle: Text(item['explanation']?.toString() ?? ''),
            ),
      ],
    );
  }
}

class _ClaimEditorDialog extends StatefulWidget {
  const _ClaimEditorDialog({this.claim});

  final ExperienceClaim? claim;

  @override
  State<_ClaimEditorDialog> createState() => _ClaimEditorDialogState();
}

class _ClaimEditorDialogState extends State<_ClaimEditorDialog> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _company;
  late final TextEditingController _jobTitle;
  late final TextEditingController _startMonth;
  late final TextEditingController _endMonth;
  late final TextEditingController _summary;
  PlatformFile? _evidence;
  bool _privacyReviewed = false;

  @override
  void initState() {
    super.initState();
    final claim = widget.claim;
    _company = TextEditingController(text: claim?.company ?? '');
    _jobTitle = TextEditingController(text: claim?.jobTitle ?? '');
    _startMonth = TextEditingController(text: claim?.startMonth ?? '');
    _endMonth = TextEditingController(text: claim?.endMonth ?? '');
    _summary = TextEditingController(text: claim?.summary ?? '');
  }

  @override
  void dispose() {
    _company.dispose();
    _jobTitle.dispose();
    _startMonth.dispose();
    _endMonth.dispose();
    _summary.dispose();
    super.dispose();
  }

  Future<void> _selectFile() async {
    final result = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: const ['pdf'],
      withData: true,
    );
    if (result == null || result.files.isEmpty) return;
    final file = result.files.single;
    final bytes = file.bytes;
    if (!file.name.toLowerCase().endsWith('.pdf') ||
        file.size <= 0 ||
        file.size > _maxEvidenceBytes ||
        bytes == null ||
        !_hasPdfSignature(bytes)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
              content: Text('Select a valid PDF no larger than 5 MiB.')),
        );
      }
      return;
    }
    setState(() {
      _evidence = file;
      _privacyReviewed = false;
    });
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
        title: Text(
          widget.claim == null
              ? 'New experience claim'
              : 'Update experience claim',
        ),
        content: SizedBox(
          width: 520,
          child: Form(
            key: _formKey,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextFormField(
                    controller: _company,
                    maxLength: 150,
                    decoration: const InputDecoration(labelText: 'Company'),
                    validator: _required,
                  ),
                  TextFormField(
                    controller: _jobTitle,
                    maxLength: 150,
                    decoration: const InputDecoration(labelText: 'Job title'),
                    validator: _required,
                  ),
                  TextFormField(
                    controller: _startMonth,
                    decoration: const InputDecoration(
                      labelText: 'Start month (YYYY-MM, optional)',
                    ),
                    validator: _month,
                  ),
                  TextFormField(
                    controller: _endMonth,
                    decoration: const InputDecoration(
                      labelText: 'End month (YYYY-MM, optional)',
                    ),
                    validator: _month,
                  ),
                  TextFormField(
                    controller: _summary,
                    maxLength: 2000,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      labelText: 'Experience summary',
                    ),
                    validator: _required,
                  ),
                  const Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Evidence is optional. Only PDF files up to 5 MiB are accepted. '
                      'Evidence is private to you and authorized reviewers.',
                    ),
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  OutlinedButton.icon(
                    onPressed: _selectFile,
                    icon: const Icon(Icons.attach_file),
                    label: Text(_evidence?.name ?? 'Choose evidence PDF'),
                  ),
                  if (_evidence?.bytes case final bytes?) ...[
                    SizedBox(
                      height: 280,
                      child: PdfViewer.data(
                        bytes,
                        sourceName: '${_evidence!.name}-${bytes.length}',
                      ),
                    ),
                    Text(
                      'Preview · ${_formatBytes(bytes.length)}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                    const Text(
                      'Permanently redact government IDs, bank details, '
                      'home addresses, and unrelated people’s information in '
                      'a trusted PDF editor before upload. Mentorax does not '
                      'alter or redact your document.',
                    ),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _privacyReviewed,
                      onChanged: (value) =>
                          setState(() => _privacyReviewed = value ?? false),
                      title: const Text(
                        'I reviewed the document and removed unnecessary sensitive information.',
                      ),
                    ),
                  ],
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
            onPressed: () {
              if (!_formKey.currentState!.validate()) return;
              if (_evidence != null && !_privacyReviewed) return;
              Navigator.pop(
                context,
                _ClaimDraft(
                  company: _company.text.trim(),
                  jobTitle: _jobTitle.text.trim(),
                  startMonth: _startMonth.text.trim(),
                  endMonth: _endMonth.text.trim(),
                  summary: _summary.text.trim(),
                  evidence: _evidence,
                ),
              );
            },
            child: const Text('Submit claim'),
          ),
        ],
      );

  String? _required(String? value) =>
      value == null || value.trim().isEmpty ? 'This field is required.' : null;

  String? _month(String? value) {
    final trimmed = value?.trim() ?? '';
    if (trimmed.isNotEmpty &&
        !RegExp(r'^\d{4}-(0[1-9]|1[0-2])$').hasMatch(trimmed)) {
      return 'Use YYYY-MM.';
    }
    return null;
  }
}

class _ClaimDraft {
  const _ClaimDraft({
    required this.company,
    required this.jobTitle,
    required this.startMonth,
    required this.endMonth,
    required this.summary,
    this.evidence,
  });

  final String company;
  final String jobTitle;
  final String startMonth;
  final String endMonth;
  final String summary;
  final PlatformFile? evidence;
}

String _eventTitle(ExperienceClaimAudit event) {
  final title = event.eventType.replaceAll('_', ' ').toLowerCase();
  if (title.isEmpty) return 'Claim updated';
  return '${title[0].toUpperCase()}${title.substring(1)}';
}

bool _hasPdfSignature(Uint8List bytes) =>
    bytes.length >= 5 && String.fromCharCodes(bytes.take(5)) == '%PDF-';

String _newRequestId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex =
      bytes.map((byte) => byte.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String _formatBytes(int bytes) => bytes < 1024 * 1024
    ? '${(bytes / 1024).ceil()} KiB'
    : '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MiB';
