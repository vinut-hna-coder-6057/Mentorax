import 'dart:convert';

import 'package:alumni_connect_mobile/app/providers.dart';
import 'package:alumni_connect_mobile/core/network/api_client.dart';
import 'package:alumni_connect_mobile/core/storage/secure_storage_service.dart';
import 'package:alumni_connect_mobile/features/app_repository.dart';
import 'package:alumni_connect_mobile/features/profile/experience_claims_section.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';

const _filePickerChannel = MethodChannel(
  'miguelruivo.flutter.plugins.filepicker',
);
final _png = Uint8List.fromList(
  base64Decode(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jU1sAAAAASUVORK5CYII=',
  ),
);

class _ClaimsRepository extends AppRepository {
  _ClaimsRepository({
    List<ExperienceClaim> claims = const [],
    Map<int, List<ExperienceClaimEvidence>> evidenceByClaim = const {},
    this.failedUploads = 0,
  }) : claims = [...claims],
       evidenceByClaim = {...evidenceByClaim},
       super(
         ApiClient(
           SecureStorageService(const FlutterSecureStorage()),
           dio: Dio(BaseOptions(baseUrl: 'https://api.example.test')),
         ),
       );

  List<ExperienceClaim> claims;
  final Map<int, List<ExperienceClaimEvidence>> evidenceByClaim;
  int failedUploads;
  final createdTypes = <ExperienceClaimType>[];
  final updatedTypes = <ExperienceClaimType>[];
  final uploadedRelationships = <String>[];
  final relatedEvidenceIds = <int?>[];
  int uploadAttempts = 0;

  @override
  Future<List<ExperienceClaim>> experienceClaims({
    int page = 0,
    int size = 50,
  }) async => claims;

  @override
  Future<ExperienceClaim> createExperienceClaim({
    required String company,
    required String jobTitle,
    required ExperienceClaimType claimType,
    required String requestId,
    required String summary,
    String? startMonth,
    String? endMonth,
  }) async {
    createdTypes.add(claimType);
    final created = ExperienceClaim(
      id: 90,
      company: company,
      jobTitle: jobTitle,
      summary: summary,
      status: 'AWAITING_EVIDENCE',
      aiStatus: 'AWAITING_EVIDENCE',
      evidenceAvailable: false,
      claimType: claimType,
      requestId: requestId,
      startMonth: startMonth,
      endMonth: endMonth,
    );
    claims = [created, ...claims];
    return created;
  }

  @override
  Future<ExperienceClaim> updateExperienceClaim(
    int id, {
    required String company,
    required String jobTitle,
    required ExperienceClaimType claimType,
    required String summary,
    required String requestId,
    String? startMonth,
    String? endMonth,
  }) async {
    updatedTypes.add(claimType);
    final previous = claims.firstWhere((claim) => claim.id == id);
    final updated = _copyClaim(
      previous,
      company: company,
      jobTitle: jobTitle,
      claimType: claimType,
      summary: summary,
      startMonth: startMonth,
      endMonth: endMonth,
      status: previous.evidenceVersion > 0 ? 'PROCESSING' : 'AWAITING_EVIDENCE',
      aiStatus: previous.evidenceVersion > 0 ? 'QUEUED' : 'AWAITING_EVIDENCE',
    );
    _replaceClaim(updated);
    return updated;
  }

  @override
  Future<List<ExperienceClaimEvidence>> experienceEvidenceHistory(
    int id,
  ) async => evidenceByClaim[id] ?? const [];

  @override
  Future<List<ExperienceClaimAudit>> experienceClaimHistory(int id) async =>
      const [];

  @override
  Future<ExperienceClaim> uploadExperienceEvidence(
    int id,
    String filename,
    Uint8List bytes, {
    String relationshipType = 'COMPLEMENTARY',
    int? relatedEvidenceId,
    ProgressCallback? onSendProgress,
  }) async {
    uploadAttempts++;
    uploadedRelationships.add(relationshipType);
    relatedEvidenceIds.add(relatedEvidenceId);
    if (failedUploads > 0) {
      failedUploads--;
      throw Exception('Temporary upload failure');
    }
    final previous = claims.firstWhere((claim) => claim.id == id);
    final uploaded = _copyClaim(
      previous,
      evidenceAvailable: true,
      evidenceVersion: previous.evidenceVersion + 1,
      evidenceFileName: filename,
      status: 'PROCESSING',
      aiStatus: 'QUEUED',
    );
    _replaceClaim(uploaded);
    return uploaded;
  }

  void _replaceClaim(ExperienceClaim updated) {
    claims = [
      for (final claim in claims)
        if (claim.id == updated.id) updated else claim,
    ];
  }
}

ExperienceClaim _copyClaim(
  ExperienceClaim claim, {
  String? company,
  String? jobTitle,
  String? summary,
  String? status,
  String? aiStatus,
  bool? evidenceAvailable,
  ExperienceClaimType? claimType,
  String? startMonth,
  String? endMonth,
  String? evidenceFileName,
  int? evidenceVersion,
}) => ExperienceClaim(
  id: claim.id,
  company: company ?? claim.company,
  jobTitle: jobTitle ?? claim.jobTitle,
  summary: summary ?? claim.summary,
  status: status ?? claim.status,
  aiStatus: aiStatus ?? claim.aiStatus,
  evidenceAvailable: evidenceAvailable ?? claim.evidenceAvailable,
  claimType: claimType ?? claim.claimType,
  requestId: claim.requestId,
  startMonth: startMonth ?? claim.startMonth,
  endMonth: endMonth ?? claim.endMonth,
  evidenceFileName: evidenceFileName ?? claim.evidenceFileName,
  evidenceVersion: evidenceVersion ?? claim.evidenceVersion,
  decisionExplanation: claim.decisionExplanation,
  aiFindings: claim.aiFindings,
);

ExperienceClaim _claim({
  int id = 7,
  String status = 'ADDITIONAL_EVIDENCE_REQUIRED',
  ExperienceClaimType claimType = ExperienceClaimType.unspecified,
  bool evidenceAvailable = true,
  int evidenceVersion = 2,
  String? aiFindings,
}) => ExperienceClaim(
  id: id,
  company: 'Northstar',
  jobTitle: 'Engineer',
  summary: 'Built reliable systems',
  status: status,
  aiStatus: 'ANALYZED',
  evidenceAvailable: evidenceAvailable,
  claimType: claimType,
  requestId: 'request-7',
  evidenceVersion: evidenceVersion,
  evidenceFileName: evidenceAvailable ? 'current.pdf' : null,
  aiFindings: aiFindings,
  decisionExplanation: status == 'INCONSISTENT'
      ? 'Documents differ. This is not a finding of fraud.'
      : null,
);

ExperienceClaimEvidence _evidence({
  required int id,
  required int version,
  required String status,
  String? relationship,
  int? relatedId,
  String filename = 'evidence.pdf',
  String analysisStatus = 'ANALYZED',
}) => ExperienceClaimEvidence(
  id: id,
  evidenceVersion: version,
  evidenceFileName: filename,
  evidenceMediaType: filename.endsWith('.png')
      ? 'image/png'
      : 'application/pdf',
  evidenceStatus: status,
  analysisStatus: analysisStatus,
  createdAt: '2026-11-12T08:00:00',
  relationshipType: relationship,
  relatedEvidenceId: relatedId,
);

void _mockImagePicker() {
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(_filePickerChannel, (call) async {
        expect(call.method, 'custom');
        return [
          {'name': 'corrected.png', 'size': _png.length, 'bytes': _png},
        ];
      });
}

Future<void> _pumpClaims(
  WidgetTester tester,
  _ClaimsRepository repository,
) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [appRepositoryProvider.overrideWithValue(repository)],
      child: const MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: Padding(
              padding: EdgeInsets.all(16),
              child: ExperienceClaimsSection(),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

Future<void> _chooseRelationship(
  WidgetTester tester, {
  required String label,
  required String relatedEvidenceLabel,
}) async {
  final relationshipDropdown = find
      .byType(DropdownButtonFormField<String>)
      .last;
  await tester.ensureVisible(relationshipDropdown);
  await tester.tap(relationshipDropdown);
  await tester.pumpAndSettle();
  await tester.tap(find.text(label).last);
  await tester.pumpAndSettle();
  final relatedEvidenceDropdown = find
      .byType(DropdownButtonFormField<int>)
      .last;
  await tester.ensureVisible(relatedEvidenceDropdown);
  await tester.tap(relatedEvidenceDropdown);
  await tester.pumpAndSettle();
  await tester.tap(find.text(relatedEvidenceLabel).last);
  await tester.pumpAndSettle();
  await tester.ensureVisible(find.text('Continue'));
  await tester.tap(find.text('Continue'));
  await tester.pumpAndSettle();
}

Future<void> _confirmImageAndPrivacy(WidgetTester tester) async {
  expect(find.byType(Image), findsOneWidget);
  final privacyConfirmation = find.byType(CheckboxListTile).last;
  await tester.ensureVisible(privacyConfirmation);
  await tester.tap(privacyConfirmation);
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(_filePickerChannel, null);
  });

  testWidgets('claim creation sends the chosen claim type', (tester) async {
    final repository = _ClaimsRepository();
    await _pumpClaims(tester, repository);

    await tester.tap(find.text('Add experience claim'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextFormField).at(0), 'Northstar');
    await tester.enterText(find.byType(TextFormField).at(1), 'Engineer');
    await tester.enterText(find.byType(TextFormField).at(4), 'Built systems');
    final claimTypeDropdown = find.byType(
      DropdownButtonFormField<ExperienceClaimType>,
    );
    await tester.ensureVisible(claimTypeDropdown);
    await tester.tap(claimTypeDropdown);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Internship').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Submit claim'));
    await tester.pumpAndSettle();

    expect(repository.createdTypes, [ExperienceClaimType.internship]);
    expect(find.text('Experience type: Internship'), findsOneWidget);
  });

  testWidgets('editing preserves an existing mentorship claim type', (
    tester,
  ) async {
    final repository = _ClaimsRepository(
      claims: [_claim(claimType: ExperienceClaimType.mentorship)],
    );
    await _pumpClaims(tester, repository);

    await tester.tap(find.text('Update requested information'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Submit claim'));
    await tester.pumpAndSettle();

    expect(repository.updatedTypes, [ExperienceClaimType.mentorship]);
    expect(find.text('Experience type: Mentorship'), findsOneWidget);
  });

  testWidgets(
    'history distinguishes current, replaced, and withdrawn evidence',
    (tester) async {
      final repository = _ClaimsRepository(
        claims: [_claim(evidenceVersion: 3)],
        evidenceByClaim: {
          7: [
            _evidence(
              id: 11,
              version: 1,
              status: 'SUPERSEDED',
              filename: 'old.pdf',
            ),
            _evidence(
              id: 12,
              version: 2,
              status: 'WITHDRAWN',
              filename: 'withdrawn.pdf',
            ),
            _evidence(
              id: 13,
              version: 3,
              status: 'ACTIVE',
              relationship: 'REPLACEMENT',
              relatedId: 11,
              filename: 'current.pdf',
            ),
          ],
        },
      );
      await _pumpClaims(tester, repository);

      await tester.tap(find.text('History'));
      await tester.pumpAndSettle();

      final historyList = find
          .descendant(
            of: find.byType(AlertDialog).last,
            matching: find.byType(Scrollable),
          )
          .last;
      expect(
        find.textContaining('Only documents marked Current are included'),
        findsOneWidget,
      );
      await tester.scrollUntilVisible(
        find.text('Version 3 · current.pdf'),
        160,
        scrollable: historyList,
      );
      expect(find.text('CURRENT'), findsOneWidget);
      expect(
        find.textContaining('Replaces version 1 (old.pdf)'),
        findsOneWidget,
      );
      await tester.scrollUntilVisible(
        find.text('Version 1 · old.pdf'),
        160,
        scrollable: historyList,
      );
      expect(find.text('REPLACED'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.text('Version 2 · withdrawn.pdf'),
        160,
        scrollable: historyList,
      );
      expect(find.text('WITHDRAWN'), findsOneWidget);
      expect(
        find.textContaining('not included in the current assessment'),
        findsNWidgets(2),
      );
    },
  );

  testWidgets('assessment summary is friendly and does not show raw excerpts', (
    tester,
  ) async {
    final repository = _ClaimsRepository(
      claims: [
        _claim(
          status: 'INCONSISTENT',
          aiFindings: jsonEncode([
            {
              'field': 'company',
              'outcome': 'MISMATCH',
              'extractedValue': 'Northstar Holdings',
              'textExcerpt': 'private raw OCR excerpt',
              'evidenceVersion': 2,
              'pageNumber': 1,
            },
          ]),
        ),
      ],
    );
    await _pumpClaims(tester, repository);

    expect(find.text('CONFLICTING INFORMATION'), findsOneWidget);
    expect(find.textContaining('not a finding of fraud'), findsOneWidget);
    await tester.tap(find.text('Document comparison summary'));
    await tester.pumpAndSettle();

    expect(find.text('Organization · Differs from the claim'), findsOneWidget);
    expect(
      find.textContaining(
        'Text identified (not independently verified): Northstar Holdings',
      ),
      findsOneWidget,
    );
    expect(find.textContaining('private raw OCR excerpt'), findsNothing);
  });

  testWidgets(
    'editing with a replacement previews PNG and links backend evidence',
    (tester) async {
      _mockImagePicker();
      final repository = _ClaimsRepository(
        claims: [
          _claim(
            claimType: ExperienceClaimType.mentorship,
            status: 'ADDITIONAL_EVIDENCE_REQUIRED',
            evidenceVersion: 1,
          ),
        ],
        evidenceByClaim: {
          7: [
            _evidence(
              id: 21,
              version: 1,
              status: 'ACTIVE',
              filename: 'certificate.pdf',
            ),
          ],
        },
      );
      await _pumpClaims(tester, repository);

      await tester.tap(find.text('Update requested information'));
      await tester.pumpAndSettle();
      final chooseEvidence = find.text('Choose evidence file');
      await tester.ensureVisible(chooseEvidence);
      await tester.tap(chooseEvidence);
      await tester.pumpAndSettle();
      await _confirmImageAndPrivacy(tester);
      await tester.ensureVisible(find.text('Submit claim'));
      await tester.tap(find.text('Submit claim'));
      await tester.pumpAndSettle();

      await _chooseRelationship(
        tester,
        label: 'Replacement',
        relatedEvidenceLabel: 'Version 1 · certificate.pdf',
      );

      expect(repository.updatedTypes, [ExperienceClaimType.mentorship]);
      expect(repository.uploadedRelationships, ['REPLACEMENT']);
      expect(repository.relatedEvidenceIds, [21]);
      expect(repository.claims.single.evidenceVersion, 2);
      expect(find.text('Experience type: Mentorship'), findsOneWidget);
    },
  );

  testWidgets(
    'failed correction upload keeps old evidence and retry reuses link',
    (tester) async {
      _mockImagePicker();
      final repository = _ClaimsRepository(
        claims: [_claim(evidenceVersion: 1)],
        evidenceByClaim: {
          7: [
            _evidence(
              id: 21,
              version: 1,
              status: 'ACTIVE',
              filename: 'certificate.pdf',
            ),
          ],
        },
        failedUploads: 1,
      );
      await _pumpClaims(tester, repository);

      final addEvidence = find.text('Add evidence');
      await tester.ensureVisible(addEvidence);
      await tester.tap(addEvidence);
      await tester.pumpAndSettle();
      await _confirmImageAndPrivacy(tester);
      await tester.tap(find.text('Use this evidence'));
      await tester.pumpAndSettle();
      await _chooseRelationship(
        tester,
        label: 'Correction',
        relatedEvidenceLabel: 'Version 1 · certificate.pdf',
      );

      expect(repository.claims.single.evidenceVersion, 1);
      expect(repository.claims.single.evidenceFileName, 'current.pdf');
      expect(find.textContaining('Evidence upload failed.'), findsOneWidget);
      await tester.tap(find.text('Retry evidence upload'));
      await tester.pumpAndSettle();

      expect(repository.uploadAttempts, 2);
      expect(repository.uploadedRelationships, ['CORRECTION', 'CORRECTION']);
      expect(repository.relatedEvidenceIds, [21, 21]);
      expect(repository.claims.single.evidenceVersion, 2);
    },
  );
}
