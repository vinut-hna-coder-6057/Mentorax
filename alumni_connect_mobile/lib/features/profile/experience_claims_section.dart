import 'dart:async';
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
const _maxProcessingRefreshAttempts = 8;
const _processingRefreshBaseDelay = Duration(seconds: 5);
const _processingRefreshMaxDelay = Duration(seconds: 30);

class ExperienceClaimsSection extends ConsumerStatefulWidget {
  const ExperienceClaimsSection({super.key});

  @override
  ConsumerState<ExperienceClaimsSection> createState() =>
      _ExperienceClaimsSectionState();
}

class _ExperienceClaimsSectionState
    extends ConsumerState<ExperienceClaimsSection> {
  List<ExperienceClaim> _claims = const [];
  final Map<int, _PendingEvidenceUpload> _evidencePendingRetry = {};
  bool _loading = true;
  bool _saving = false;
  int? _uploadingClaimId;
  double? _uploadProgress;
  Timer? _processingRefreshTimer;
  bool _refreshInFlight = false;
  int _processingRefreshAttempts = 0;
  bool _processingRefreshFailed = false;
  bool _processingRefreshExhausted = false;

  @override
  void initState() {
    super.initState();
    _loadClaims();
  }

  Future<void> _loadClaims({bool silent = false}) async {
    if (!silent) {
      _processingRefreshTimer?.cancel();
      _processingRefreshTimer = null;
      _processingRefreshAttempts = 0;
      _processingRefreshFailed = false;
      _processingRefreshExhausted = false;
    }
    try {
      final claims = await ref.read(appRepositoryProvider).experienceClaims();
      if (!mounted) return;
      setState(() {
        _claims = claims;
        _loading = false;
        _processingRefreshFailed = false;
      });
      if (!silent) _scheduleProcessingRefresh();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        if (silent) _processingRefreshFailed = true;
      });
      if (!silent) {
        _showMessage(
          'Could not load experience claims: ${userFacingError(error)}',
        );
        _scheduleProcessingRefresh();
      }
    }
  }

  void _scheduleProcessingRefresh() {
    if (!mounted) return;
    final hasProcessingClaim = _claims.any(
      (claim) => claim.status.toUpperCase() == 'PROCESSING',
    );
    if (!hasProcessingClaim) {
      _processingRefreshTimer?.cancel();
      _processingRefreshTimer = null;
      _processingRefreshAttempts = 0;
      _processingRefreshFailed = false;
      _processingRefreshExhausted = false;
      return;
    }
    if (_processingRefreshTimer != null ||
        _refreshInFlight ||
        _processingRefreshExhausted ||
        _processingRefreshAttempts >= _maxProcessingRefreshAttempts) {
      return;
    }
    final backoffSeconds = min(
      _processingRefreshMaxDelay.inSeconds,
      _processingRefreshBaseDelay.inSeconds * (1 << _processingRefreshAttempts),
    );
    _processingRefreshTimer = Timer(
      Duration(seconds: backoffSeconds),
      _refreshProcessingClaims,
    );
  }

  Future<void> _refreshProcessingClaims() async {
    _processingRefreshTimer = null;
    if (!mounted ||
        _refreshInFlight ||
        !_claims.any((claim) => claim.status.toUpperCase() == 'PROCESSING')) {
      return;
    }
    _refreshInFlight = true;
    _processingRefreshAttempts++;
    try {
      await _loadClaims(silent: true);
    } finally {
      _refreshInFlight = false;
      if (mounted) {
        final hasProcessingClaim = _claims.any(
          (claim) => claim.status.toUpperCase() == 'PROCESSING',
        );
        if (!hasProcessingClaim) {
          setState(() {
            _processingRefreshAttempts = 0;
            _processingRefreshFailed = false;
            _processingRefreshExhausted = false;
          });
        } else if (_processingRefreshAttempts >=
            _maxProcessingRefreshAttempts) {
          setState(() => _processingRefreshExhausted = true);
        } else {
          _scheduleProcessingRefresh();
        }
      }
    }
  }

  @override
  void dispose() {
    _processingRefreshTimer?.cancel();
    super.dispose();
  }

  void _showMessage(String message) {
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _createClaim() async {
    final draft = await showDialog<_ClaimDraft>(
      context: context,
      builder: (_) => const _ClaimEditorDialog(),
    );
    if (draft == null || !mounted) return;
    setState(() => _saving = true);
    try {
      final claim = await ref
          .read(appRepositoryProvider)
          .createExperienceClaim(
            company: draft.company,
            jobTitle: draft.jobTitle,
            claimType: draft.claimType,
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
        _showMessage('Claim submitted. Add a PDF to start automatic analysis.');
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
    final relationship = draft.evidence == null
        ? null
        : await _chooseEvidenceRelationship(claim.id);
    if (draft.evidence != null && (relationship == null || !mounted)) return;
    setState(() => _saving = true);
    try {
      final updated = await ref
          .read(appRepositoryProvider)
          .updateExperienceClaim(
            claim.id,
            company: draft.company,
            jobTitle: draft.jobTitle,
            claimType: draft.claimType,
            requestId: claim.requestId ?? _newRequestId(),
            startMonth: draft.startMonth,
            endMonth: draft.endMonth,
            summary: draft.summary,
          );
      if (!mounted) return;
      setState(() => _replaceClaim(updated));
      if (draft.evidence != null) {
        await _uploadEvidence(
          updated.id,
          draft.evidence!,
          relationshipType: relationship!.relationshipType,
          relatedEvidenceId: relationship.relatedEvidenceId,
        );
      } else {
        _showMessage(
          updated.decisionExplanation ??
              'Claim details updated. Add evidence to continue.',
        );
      }
    } catch (error) {
      if (mounted) {
        _showMessage('Could not update claim: ${userFacingError(error)}');
      }
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
    if (reviewed != true || !mounted) return;
    final relationship = await _chooseEvidenceRelationship(claimId);
    if (relationship == null || !mounted) return;
    await _uploadEvidence(
      claimId,
      picked,
      relationshipType: relationship.relationshipType,
      relatedEvidenceId: relationship.relatedEvidenceId,
    );
  }

  Future<PlatformFile?> _pickEvidence() async {
    try {
      final result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['pdf', 'jpg', 'jpeg', 'png'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) return null;
      final file = result.files.single;
      final bytes = file.bytes;
      if (file.size <= 0 ||
          file.size > _maxEvidenceBytes ||
          bytes == null ||
          !_isSupportedEvidence(file.name, bytes)) {
        _showMessage(
          'Select a valid PDF, JPG, JPEG, or PNG no larger than 5 MiB.',
        );
        return null;
      }
      return file;
    } catch (error) {
      if (mounted) {
        _showMessage('Could not select evidence: ${userFacingError(error)}');
      }
      return null;
    }
  }

  Future<_EvidenceRelationship?> _chooseEvidenceRelationship(
    int claimId,
  ) async {
    try {
      final history = await ref
          .read(appRepositoryProvider)
          .experienceEvidenceHistory(claimId);
      final activeEvidence = history
          .where((item) => item.evidenceStatus.toUpperCase() == 'ACTIVE')
          .toList();
      if (!mounted) return null;
      if (activeEvidence.isEmpty) {
        return const _EvidenceRelationship('COMPLEMENTARY');
      }
      var relationshipType = 'COMPLEMENTARY';
      int? relatedEvidenceId;
      return await showDialog<_EvidenceRelationship>(
        context: context,
        builder: (context) => StatefulBuilder(
          builder: (context, setDialogState) => AlertDialog(
            title: const Text('Evidence relationship'),
            content: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DropdownButtonFormField<String>(
                  initialValue: relationshipType,
                  decoration: const InputDecoration(labelText: 'Relationship'),
                  items: const [
                    DropdownMenuItem(
                      value: 'COMPLEMENTARY',
                      child: Text('Complementary evidence'),
                    ),
                    DropdownMenuItem(
                      value: 'REPLACEMENT',
                      child: Text('Replacement'),
                    ),
                    DropdownMenuItem(
                      value: 'CORRECTION',
                      child: Text('Correction'),
                    ),
                    DropdownMenuItem(
                      value: 'CONFLICTS_WITH',
                      child: Text('Conflicts with another document'),
                    ),
                  ],
                  onChanged: (value) => setDialogState(
                    () => relationshipType = value ?? 'COMPLEMENTARY',
                  ),
                ),
                const SizedBox(height: AppSpacing.xs),
                Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    experienceEvidenceRelationshipExplanation(relationshipType),
                  ),
                ),
                if (relationshipType != 'COMPLEMENTARY') ...[
                  const SizedBox(height: AppSpacing.sm),
                  DropdownButtonFormField<int>(
                    initialValue: relatedEvidenceId,
                    decoration: const InputDecoration(
                      labelText: 'Related active document',
                    ),
                    items: [
                      for (final evidence in activeEvidence)
                        DropdownMenuItem(
                          value: evidence.id,
                          child: Text(
                            'Version ${evidence.evidenceVersion} · '
                            '${evidence.evidenceFileName}',
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                    ],
                    onChanged: (value) =>
                        setDialogState(() => relatedEvidenceId = value),
                  ),
                ],
              ],
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Cancel'),
              ),
              FilledButton(
                onPressed:
                    relationshipType != 'COMPLEMENTARY' &&
                        relatedEvidenceId == null
                    ? null
                    : () => Navigator.pop(
                        context,
                        _EvidenceRelationship(
                          relationshipType,
                          relatedEvidenceId: relatedEvidenceId,
                        ),
                      ),
                child: const Text('Continue'),
              ),
            ],
          ),
        ),
      );
    } catch (error) {
      if (mounted) {
        _showMessage(
          'Could not load evidence relationships: ${userFacingError(error)}',
        );
      }
      return null;
    }
  }

  Future<void> _uploadEvidence(
    int claimId,
    PlatformFile file, {
    String relationshipType = 'COMPLEMENTARY',
    int? relatedEvidenceId,
  }) async {
    final bytes = file.bytes;
    if (bytes == null || bytes.isEmpty) {
      _showMessage(
        'The selected evidence is no longer available. Select it again.',
      );
      return;
    }
    _evidencePendingRetry[claimId] = _PendingEvidenceUpload(
      file,
      relationshipType,
      relatedEvidenceId,
    );
    final claimIndex = _claims.indexWhere((claim) => claim.id == claimId);
    final previousEvidenceVersion = claimIndex >= 0
        ? _claims[claimIndex].evidenceVersion
        : null;
    setState(() {
      _uploadingClaimId = claimId;
      _uploadProgress = 0;
    });
    try {
      final updated = await ref
          .read(appRepositoryProvider)
          .uploadExperienceEvidence(
            claimId,
            file.name,
            bytes,
            relationshipType: relationshipType,
            relatedEvidenceId: relatedEvidenceId,
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
      _showMessage(
        previousEvidenceVersion == updated.evidenceVersion
            ? 'This file is already in claim history; no new analysis was queued.'
            : 'Evidence uploaded. '
                  '${experienceEvidenceRelationshipExplanation(relationshipType)}',
      );
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
    final pending = _evidencePendingRetry[claimId];
    if (pending == null) {
      await _chooseEvidence(claimId);
      return;
    }
    await _uploadEvidence(
      claimId,
      pending.file,
      relationshipType: pending.relationshipType,
      relatedEvidenceId: pending.relatedEvidenceId,
    );
  }

  Future<void> _deleteEvidence(ExperienceClaim claim) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete private evidence?'),
        content: const Text(
          'Current evidence will be withdrawn from this claim. Evidence '
          'versions and their analysis history will remain available in claim history.',
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
      _showMessage('Current evidence withdrawn; evidence history preserved.');
    } catch (error) {
      if (mounted) {
        _showMessage('Could not delete evidence: ${userFacingError(error)}');
      }
    }
  }

  Future<void> _viewEvidence(ExperienceClaim claim) async {
    try {
      final bytes = await ref
          .read(appRepositoryProvider)
          .experienceEvidence(claim.id);
      if (!mounted) return;
      await _showPdfPreview(
        bytes,
        claim.evidenceFileName ?? 'private-evidence.pdf',
        requirePrivacyConfirmation: false,
      );
    } catch (error) {
      if (mounted) {
        _showMessage(
          'Could not open private evidence: ${userFacingError(error)}',
        );
      }
    }
  }

  Future<void> _showHistory(ExperienceClaim claim) async {
    try {
      final repository = ref.read(appRepositoryProvider);
      final history = await repository.experienceClaimHistory(claim.id);
      final evidenceHistory = await repository.experienceEvidenceHistory(
        claim.id,
      );
      if (!mounted) return;
      await showDialog<void>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Claim history'),
          content: SizedBox(
            width: 480,
            height: 560,
            child: history.isEmpty && evidenceHistory.isEmpty
                ? const Center(child: Text('No history is available.'))
                : _historyContents(claim.id, evidenceHistory, history),
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
        _showMessage('Could not load claim history: ${userFacingError(error)}');
      }
    }
  }

  Widget _historyContents(
    int claimId,
    List<ExperienceClaimEvidence> evidenceHistory,
    List<ExperienceClaimAudit> history,
  ) => ListView(
    shrinkWrap: true,
    children: [
      const Padding(
        padding: EdgeInsets.only(bottom: AppSpacing.sm),
        child: Text(
          'Older documents remain visible for your records. Only documents '
          'marked Current are included in the current assessment.',
        ),
      ),
      if (evidenceHistory.isNotEmpty) ...[
        const ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text('Evidence versions'),
        ),
        for (final evidence in evidenceHistory)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: Icon(switch (experienceEvidenceLifecycle(
              evidence.evidenceStatus,
            )) {
              ExperienceEvidenceLifecycle.active => Icons.description_outlined,
              ExperienceEvidenceLifecycle.superseded ||
              ExperienceEvidenceLifecycle.withdrawn => Icons.history,
              ExperienceEvidenceLifecycle.unknown => Icons.help_outline,
            }),
            title: Text(
              'Version ${evidence.evidenceVersion} · ${evidence.evidenceFileName}',
            ),
            trailing: StatusBadge(
              experienceEvidenceLifecycleLabel(evidence.evidenceStatus),
            ),
            subtitle: Text(
              [
                experienceEvidenceLifecycleExplanation(evidence.evidenceStatus),
                _evidenceRelationshipHistory(evidence, evidenceHistory),
                'Analysis: ${experienceAnalysisStatusLabel(evidence.analysisStatus)}',
                if (evidence.createdAt.isNotEmpty)
                  'Added ${evidence.createdAt}',
              ].join('\n'),
            ),
            onTap: () => _viewEvidenceVersion(claimId, evidence),
          ),
      ],
      if (history.isNotEmpty) ...[
        const ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text('Claim activity'),
        ),
        for (final event in history)
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text(_eventTitle(event)),
            subtitle: Text(
              [
                if (event.oldStatus != null || event.newStatus != null)
                  '${event.oldStatus == null ? '—' : experienceClaimStatusLabel(event.oldStatus)}'
                      ' → '
                      '${event.newStatus == null ? '—' : experienceClaimStatusLabel(event.newStatus)}',
                if (event.eventNote?.isNotEmpty == true) event.eventNote!,
                event.createdAt,
              ].join('\n'),
            ),
          ),
      ],
    ],
  );

  Future<void> _viewEvidenceVersion(
    int claimId,
    ExperienceClaimEvidence evidence,
  ) async {
    try {
      final bytes = await ref
          .read(appRepositoryProvider)
          .experienceEvidenceVersion(claimId, evidence.id);
      if (!mounted) return;
      await _showPdfPreview(
        bytes,
        evidence.evidenceFileName,
        requirePrivacyConfirmation: false,
      );
    } catch (error) {
      if (mounted) {
        _showMessage(
          'Could not open evidence version: ${userFacingError(error)}',
        );
      }
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
      final updated = await ref
          .read(appRepositoryProvider)
          .appealExperienceClaim(claim.id, note);
      if (!mounted) return;
      setState(() => _replaceClaim(updated));
      _showMessage('Appeal submitted for automatic reassessment.');
    } catch (error) {
      if (mounted) {
        _showMessage('Could not submit appeal: ${userFacingError(error)}');
      }
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
    final isPdf = filename.toLowerCase().endsWith('.pdf');
    final isImage =
        filename.toLowerCase().endsWith('.png') ||
        filename.toLowerCase().endsWith('.jpg') ||
        filename.toLowerCase().endsWith('.jpeg');
    if ((!isPdf && !isImage) || !_isSupportedEvidence(filename, bytes)) {
      _showMessage('This document format is not supported.');
      return false;
    }
    var privacyReviewed = !requirePrivacyConfirmation;
    return showDialog<bool>(
      context: context,
      builder: (context) => StatefulBuilder(
        builder: (context, setDialogState) => AlertDialog(
          title: Text(
            requirePrivacyConfirmation ? 'Review evidence' : filename,
          ),
          content: SizedBox(
            width: 640,
            height: 560,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: isPdf
                        ? PdfViewer.data(
                            bytes,
                            sourceName: '$filename-${bytes.length}',
                          )
                        : Image.memory(bytes, fit: BoxFit.contain),
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
                    'trusted editor before uploading. Redaction cannot be '
                    'performed inside Mentorax.',
                  ),
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    value: privacyReviewed,
                    onChanged: (value) =>
                        setDialogState(() => privacyReviewed = value ?? false),
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
                onPressed: privacyReviewed
                    ? () => Navigator.pop(context, true)
                    : null,
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
    _scheduleProcessingRefresh();
  }

  ExperienceClaim _withoutEvidence(ExperienceClaim claim) => ExperienceClaim(
    id: claim.id,
    company: claim.company,
    jobTitle: claim.jobTitle,
    claimType: claim.claimType,
    requestId: claim.requestId,
    summary: claim.summary,
    status: 'AWAITING_EVIDENCE',
    aiStatus: 'AWAITING_EVIDENCE',
    evidenceAvailable: false,
    startMonth: claim.startMonth,
    endMonth: claim.endMonth,
    evidenceSummary: claim.evidenceSummary,
    appealNote: claim.appealNote,
    evidenceVersion: claim.evidenceVersion,
    decisionExplanation:
        'Upload a supporting document to begin evidence analysis.',
    corroborationStatus: 'NOT_CHECKED',
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
            const Expanded(child: AppSectionTitle('Experience verification')),
            IconButton(
              tooltip: 'Refresh claims',
              onPressed: _loading ? null : _loadClaims,
              icon: const Icon(Icons.refresh),
            ),
          ],
        ),
        const Text(
          'Evidence is analysed automatically. A document-based result is '
          'not independent confirmation from the employer.',
        ),
        const SizedBox(height: AppSpacing.md),
        if (_loading)
          const Center(child: CircularProgressIndicator())
        else if (_claims.isEmpty)
          const Text('No experience claims have been submitted.')
        else
          for (final claim in _claims) _claimCard(claim),
        if (!_loading && _processingRefreshExhausted)
          const Padding(
            padding: EdgeInsets.only(top: AppSpacing.xs),
            child: Text(
              'Automatic status checks stopped. Refresh claims to check again.',
            ),
          )
        else if (!_loading && _processingRefreshFailed)
          const Padding(
            padding: EdgeInsets.only(top: AppSpacing.xs),
            child: Text(
              'Could not refresh processing status. It will retry automatically.',
            ),
          ),
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
    final canEdit = const {
      'NEEDS_INFO',
      'ADDITIONAL_EVIDENCE_REQUIRED',
      'INCONSISTENT',
      'INSUFFICIENT_EVIDENCE',
      'PROCESSING_FAILED',
    }.contains(claim.status.toUpperCase());
    final isRejected = claim.status.toUpperCase() == 'REJECTED';
    final canUpload = _canUploadEvidence(claim.status);
    final isVerified = const {
      'VERIFIED',
      'VERIFIED_FROM_EVIDENCE',
      'EMPLOYER_CONFIRMED',
    }.contains(claim.status.toUpperCase());
    final processingFailed = claim.status.toUpperCase() == 'PROCESSING_FAILED';
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
                StatusBadge(_claimStatusLabel(claim.status)),
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
            Padding(
              padding: const EdgeInsets.only(top: AppSpacing.xs),
              child: Text(
                claim.claimType == ExperienceClaimType.unspecified
                    ? 'Experience type: Unspecified — not classified as an internship or mentorship.'
                    : 'Experience type: ${claim.claimType.label}',
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            Text(
              'Document analysis: ${experienceAnalysisStatusLabel(claim.aiStatus)}',
            ),
            if (claim.ocrStatus.toUpperCase() == 'FAILED')
              const Text(
                'Some document text could not be read. This does not mean the claim is false.',
              ),
            if (claim.decisionExplanation?.isNotEmpty == true) ...[
              const SizedBox(height: AppSpacing.xs),
              Text(claim.decisionExplanation!),
            ] else ...[
              const SizedBox(height: AppSpacing.xs),
              Text(experienceClaimStatusExplanation(claim.status)),
            ],
            if (isVerified &&
                claim.status.toUpperCase() == 'EMPLOYER_CONFIRMED') ...[
              const SizedBox(height: AppSpacing.xs),
              Text(
                'An independent verification provider confirmed this claim '
                '(${claim.corroborationStatus.replaceAll('_', ' ')}).',
              ),
            ] else if (isVerified) ...[
              const SizedBox(height: AppSpacing.xs),
              const Text(
                'This result is based on submitted documents. Employer '
                'confirmation was not independently obtained.',
              ),
            ],
            if (claim.requestedEvidence?.isNotEmpty == true)
              _requestedEvidence(claim.requestedEvidence!),
            if (claim.aiFindings?.isNotEmpty == true)
              _findings(claim.aiFindings!)
            else if (const {
              'ANALYZED',
              'INCONCLUSIVE',
            }.contains(claim.aiStatus.toUpperCase()))
              const Text(
                'Detailed saved extraction fields are not available in this response.',
              ),
            if (isUploading) ...[
              const SizedBox(height: AppSpacing.sm),
              LinearProgressIndicator(value: _uploadProgress),
              if (_uploadProgress != null)
                Text('${(_uploadProgress! * 100).round()}% uploaded'),
            ],
            if (claim.evidenceAvailable) ...[
              const SizedBox(height: AppSpacing.sm),
              Text('Current evidence: ${claim.evidenceFileName ?? 'Document'}'),
              const Text(
                'Only evidence marked Current in History participates in the assessment.',
              ),
            ] else if (claim.evidenceVersion > 0) ...[
              const SizedBox(height: AppSpacing.sm),
              const Text(
                'No current document is attached. Earlier versions remain in History.',
              ),
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
                    onPressed: isUploading
                        ? null
                        : () => _deleteEvidence(claim),
                    child: const Text('Delete evidence'),
                  ),
                if (canUpload)
                  OutlinedButton.icon(
                    onPressed: isUploading
                        ? null
                        : () => _retryEvidence(claim.id),
                    icon: Icon(
                      _evidencePendingRetry.containsKey(claim.id)
                          ? Icons.refresh
                          : Icons.attach_file,
                    ),
                    label: Text(
                      _evidencePendingRetry.containsKey(claim.id)
                          ? 'Retry evidence upload'
                          : claim.evidenceAvailable
                          ? 'Add evidence'
                          : 'Upload evidence',
                    ),
                  ),
                if (processingFailed && claim.evidenceAvailable)
                  OutlinedButton.icon(
                    onPressed: isUploading ? null : () => _reassess(claim),
                    icon: const Icon(Icons.refresh),
                    label: const Text('Retry analysis'),
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
      title: const Text('Document comparison summary'),
      subtitle: const Text(
        'Extracted text is not independent confirmation of the document or issuer. '
        'Full saved fields and confidence details are not exposed here.',
      ),
      children: [
        for (final item in findings)
          if (item is Map)
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(
                '${_findingFieldLabel(item['field'])} · '
                '${experienceFindingOutcomeLabel(_optionalString(item['outcome']))}',
              ),
              subtitle: Text(
                [
                  if (item['extractedValue'] case final String value
                      when value.trim().isNotEmpty)
                    'Text identified (not independently verified): ${_boundedText(value, 160)}',
                  if (item['evidenceVersion'] case final num version)
                    'Document version: ${version.toInt()}',
                  if (item['pageNumber'] case final num page)
                    'Page ${page.toInt()}',
                  if (item['explanation'] case final String explanation
                      when explanation.trim().isNotEmpty)
                    _boundedText(explanation, 240),
                ].join('\n'),
              ),
            ),
      ],
    );
  }

  Widget _requestedEvidence(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is List && decoded.isNotEmpty) {
        return Padding(
          padding: const EdgeInsets.only(top: AppSpacing.xs),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text('Additional evidence requested:'),
              for (final item in decoded)
                Padding(
                  padding: const EdgeInsets.only(left: AppSpacing.md, top: 2),
                  child: Text('• $item'),
                ),
            ],
          ),
        );
      }
    } on FormatException {
      return Text('Additional evidence requested: $raw');
    }
    return const SizedBox.shrink();
  }

  Future<void> _reassess(ExperienceClaim claim) async {
    try {
      final updated = await ref
          .read(appRepositoryProvider)
          .reassessExperienceClaim(claim.id);
      if (!mounted) return;
      setState(() => _replaceClaim(updated));
      _showMessage('Evidence analysis has been queued again.');
    } catch (error) {
      if (mounted) {
        _showMessage('Could not retry analysis: ${userFacingError(error)}');
      }
    }
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
  late ExperienceClaimType _claimType;

  @override
  void initState() {
    super.initState();
    final claim = widget.claim;
    _claimType = claim?.claimType ?? ExperienceClaimType.unspecified;
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
    try {
      final result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['pdf', 'jpg', 'jpeg', 'png'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) return;
      final file = result.files.single;
      final bytes = file.bytes;
      if (file.size <= 0 ||
          file.size > _maxEvidenceBytes ||
          bytes == null ||
          !_isSupportedEvidence(file.name, bytes)) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'Select a valid PDF, JPG, JPEG, or PNG no larger than 5 MiB.',
              ),
            ),
          );
        }
        return;
      }
      setState(() {
        _evidence = file;
        _privacyReviewed = false;
      });
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Could not select evidence: ${userFacingError(error)}',
            ),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(
      widget.claim == null ? 'New experience claim' : 'Update experience claim',
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
              DropdownButtonFormField<ExperienceClaimType>(
                initialValue: _claimType,
                decoration: const InputDecoration(labelText: 'Experience type'),
                items: ExperienceClaimType.values
                    .map(
                      (type) => DropdownMenuItem(
                        value: type,
                        child: Text(type.label),
                      ),
                    )
                    .toList(),
                onChanged: (value) {
                  if (value != null) setState(() => _claimType = value);
                },
              ),
              if (_claimType == ExperienceClaimType.unspecified)
                const Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    'Unspecified stays unclassified; Mentorax will not infer an internship or mentorship.',
                  ),
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
                  'Evidence is optional. PDF, JPG, JPEG, and PNG files up to 5 MiB are supported. '
                  'Uploaded evidence is encrypted and analysed automatically.',
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              OutlinedButton.icon(
                onPressed: _selectFile,
                icon: const Icon(Icons.attach_file),
                label: Text(_evidence?.name ?? 'Choose evidence file'),
              ),
              if (_evidence?.bytes case final bytes?) ...[
                SizedBox(
                  height: 280,
                  child: _evidence!.name.toLowerCase().endsWith('.pdf')
                      ? PdfViewer.data(
                          bytes,
                          sourceName: '${_evidence!.name}-${bytes.length}',
                        )
                      : Image.memory(bytes, fit: BoxFit.contain),
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
              claimType: _claimType,
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
    required this.claimType,
    required this.startMonth,
    required this.endMonth,
    required this.summary,
    this.evidence,
  });

  final String company;
  final String jobTitle;
  final ExperienceClaimType claimType;
  final String startMonth;
  final String endMonth;
  final String summary;
  final PlatformFile? evidence;
}

class _EvidenceRelationship {
  const _EvidenceRelationship(this.relationshipType, {this.relatedEvidenceId});

  final String relationshipType;
  final int? relatedEvidenceId;
}

class _PendingEvidenceUpload {
  const _PendingEvidenceUpload(
    this.file,
    this.relationshipType,
    this.relatedEvidenceId,
  );

  final PlatformFile file;
  final String relationshipType;
  final int? relatedEvidenceId;
}

String _eventTitle(ExperienceClaimAudit event) {
  final title = event.eventType.replaceAll('_', ' ').toLowerCase();
  if (title.isEmpty) return 'Claim updated';
  return '${title[0].toUpperCase()}${title.substring(1)}';
}

String _evidenceRelationshipHistory(
  ExperienceClaimEvidence evidence,
  List<ExperienceClaimEvidence> history,
) {
  final relationship = evidence.relationshipType?.toUpperCase();
  if (relationship == null) return 'Relationship details are not available.';
  if (relationship == 'COMPLEMENTARY') {
    return experienceEvidenceRelationshipExplanation(relationship);
  }
  ExperienceClaimEvidence? related;
  for (final item in history) {
    if (item.id == evidence.relatedEvidenceId) {
      related = item;
      break;
    }
  }
  final linkedDocument = related == null
      ? 'the linked document'
      : 'version ${related.evidenceVersion} (${related.evidenceFileName})';
  return switch (relationship) {
    'REPLACEMENT' =>
      'Replaces $linkedDocument. The earlier version remains in history.',
    'CORRECTION' =>
      'Corrects $linkedDocument. The earlier version remains in history.',
    'CONFLICTS_WITH' =>
      'Differs from $linkedDocument. A difference alone is not a finding of fraud.',
    _ => experienceEvidenceRelationshipExplanation(relationship),
  };
}

String _findingFieldLabel(Object? field) => switch (field) {
  'employeeName' || 'personName' => 'Name',
  'company' || 'organisation' || 'companyName' => 'Organization',
  'jobTitle' || 'role' => 'Role',
  'startMonth' || 'startDate' => 'Start date',
  'endMonth' || 'endDate' => 'End date',
  'duplicateDocument' => 'Possible duplicate document',
  _ => 'Document detail',
};

String? _optionalString(Object? value) =>
    value is String && value.trim().isNotEmpty ? value : null;

String _boundedText(String value, int maximum) =>
    value.length <= maximum ? value : '${value.substring(0, maximum - 1)}…';

bool _canUploadEvidence(String status) => const {
  'PENDING',
  'AWAITING_EVIDENCE',
  'PROCESSING',
  'NEEDS_INFO',
  'ADDITIONAL_EVIDENCE_REQUIRED',
  'INCONSISTENT',
  'INSUFFICIENT_EVIDENCE',
  'PROCESSING_FAILED',
  'VERIFIED',
  'VERIFIED_FROM_EVIDENCE',
  'EMPLOYER_CONFIRMED',
}.contains(status.toUpperCase());

String _claimStatusLabel(String status) => experienceClaimStatusLabel(status);

bool _hasPdfSignature(Uint8List bytes) =>
    bytes.length >= 5 && String.fromCharCodes(bytes.take(5)) == '%PDF-';

bool _isSupportedEvidence(String filename, Uint8List bytes) {
  final name = filename.toLowerCase();
  if (name.endsWith('.pdf')) return _hasPdfSignature(bytes);
  if (name.endsWith('.png')) {
    return bytes.length >= 8 &&
        bytes.take(8).join(',') == '137,80,78,71,13,10,26,10';
  }
  if (name.endsWith('.jpg') || name.endsWith('.jpeg')) {
    return bytes.length >= 3 &&
        bytes[0] == 0xff &&
        bytes[1] == 0xd8 &&
        bytes[2] == 0xff;
  }
  return false;
}

String _newRequestId() {
  final random = Random.secure();
  final bytes = List<int>.generate(16, (_) => random.nextInt(256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  final hex = bytes
      .map((byte) => byte.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-'
      '${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String _formatBytes(int bytes) => bytes < 1024 * 1024
    ? '${(bytes / 1024).ceil()} KiB'
    : '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MiB';
