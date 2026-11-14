import 'package:flutter_test/flutter_test.dart';
import 'package:alumni_connect_mobile/shared/models/models.dart';

void main() {
  test('maps backend user roles safely', () {
    expect(userRole('STUDENT'), UserRole.student);
    expect(userRole('ALUMNI'), UserRole.alumni);
    expect(userRole('ADMIN'), UserRole.admin);
    expect(userRole('unexpected'), UserRole.unknown);
  });

  test('maps a backend user DTO to the domain model', () {
    final user = User.fromJson({
      'id': 7,
      'name': 'Asha',
      'email': 'asha@example.com',
      'role': 'ALUMNI',
      'status': 'APPROVED',
    });
    expect(user.id, 7);
    expect(user.role, UserRole.alumni);
    expect(user.status, 'APPROVED');
  });

  test('maps evidence lifecycle and relationship fields', () {
    final evidence = ExperienceClaimEvidence.fromJson({
      'id': 12,
      'evidenceVersion': 3,
      'evidenceFileName': 'completion.png',
      'evidenceMediaType': 'image/png',
      'evidenceSize': 256,
      'analysisStatus': 'ANALYZED',
      'evidenceStatus': 'SUPERSEDED',
      'relationshipType': 'REPLACEMENT',
      'relatedEvidenceId': 9,
      'createdAt': '2026-11-12T08:00:00',
    });

    expect(evidence.evidenceVersion, 3);
    expect(evidence.evidenceStatus, 'SUPERSEDED');
    expect(evidence.relationshipType, 'REPLACEMENT');
    expect(evidence.relatedEvidenceId, 9);
    expect(evidence.evidenceMediaType, 'image/png');
    expect(
      experienceEvidenceLifecycleLabel(evidence.evidenceStatus),
      'Replaced',
    );
    expect(
      experienceEvidenceLifecycleExplanation(evidence.evidenceStatus),
      contains('not included in the current assessment'),
    );
    expect(
      experienceEvidenceRelationshipLabel(evidence.relationshipType),
      'Replaces an earlier document',
    );
    expect(
      experienceEvidenceRelationshipExplanation('CONFLICTS_WITH'),
      contains('not a finding of fraud'),
    );
  });

  test('maps claim assessment statuses without overclaiming authenticity', () {
    expect(
      experienceClaimStatusLabel('VERIFIED_FROM_EVIDENCE'),
      'Supported by submitted evidence',
    );
    expect(
      experienceClaimStatusExplanation('VERIFIED_FROM_EVIDENCE'),
      contains('does not guarantee document authenticity'),
    );
    expect(
      experienceClaimStatusLabel('INCONSISTENT'),
      'Conflicting information',
    );
    expect(
      experienceClaimStatusExplanation('INCONSISTENT'),
      contains('not by itself a finding of fraud'),
    );
    expect(
      experienceAnalysisStatusLabel('INCONCLUSIVE'),
      contains('not a fraud finding'),
    );
    expect(experienceFindingOutcomeLabel('MISMATCH'), 'Differs from the claim');
    expect(
      experienceClaimStatusLabel('NEW_BACKEND_STATE'),
      'Status unavailable',
    );
  });

  test(
    'maps claim types, serializes supported API values, and defaults legacy claims',
    () {
      expect(experienceClaimType('INTERNSHIP'), ExperienceClaimType.internship);
      expect(experienceClaimType('MENTORSHIP'), ExperienceClaimType.mentorship);
      expect(experienceClaimType(null), ExperienceClaimType.unspecified);
      expect(ExperienceClaimType.internship.apiValue, 'INTERNSHIP');
      expect(ExperienceClaimType.mentorship.apiValue, 'MENTORSHIP');
      expect(ExperienceClaimType.unspecified.apiValue, 'UNSPECIFIED');
      expect(ExperienceClaimType.internship.label, 'Internship');
      expect(ExperienceClaimType.mentorship.label, 'Mentorship');
      expect(ExperienceClaimType.unspecified.label, 'Unspecified');

      final legacy = ExperienceClaim.fromJson({
        'id': 12,
        'company': 'Northstar',
        'jobTitle': 'Engineer',
        'summary': 'Built systems',
        'status': 'PENDING',
        'aiStatus': 'PENDING',
        'evidenceAvailable': false,
      });
      expect(legacy.claimType, ExperienceClaimType.unspecified);
      expect(legacy.status, 'PENDING');
    },
  );

  test('missing lifecycle and analysis states are unknown, not current', () {
    final legacyEvidence = ExperienceClaimEvidence.fromJson({
      'id': 14,
      'evidenceVersion': 1,
      'evidenceFileName': 'legacy.pdf',
      'evidenceMediaType': 'application/pdf',
      'createdAt': '2026-11-12T08:00:00',
    });

    expect(legacyEvidence.evidenceStatus, 'UNKNOWN');
    expect(
      experienceEvidenceLifecycleLabel(legacyEvidence.evidenceStatus),
      'Status unavailable',
    );
    expect(legacyEvidence.analysisStatus, 'UNKNOWN');
    expect(
      experienceAnalysisStatusLabel(legacyEvidence.analysisStatus),
      'Analysis status unavailable',
    );
  });
}
