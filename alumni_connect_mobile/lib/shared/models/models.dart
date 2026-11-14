enum UserRole { student, alumni, admin, unknown }

enum ExperienceClaimType { internship, mentorship, unspecified }

extension ExperienceClaimTypeDetails on ExperienceClaimType {
  String get apiValue => switch (this) {
    ExperienceClaimType.internship => 'INTERNSHIP',
    ExperienceClaimType.mentorship => 'MENTORSHIP',
    ExperienceClaimType.unspecified => 'UNSPECIFIED',
  };

  String get label => switch (this) {
    ExperienceClaimType.internship => 'Internship',
    ExperienceClaimType.mentorship => 'Mentorship',
    ExperienceClaimType.unspecified => 'Unspecified',
  };
}

enum ExperienceEvidenceLifecycle { active, superseded, withdrawn, unknown }

ExperienceEvidenceLifecycle experienceEvidenceLifecycle(String? value) =>
    switch (value?.toUpperCase()) {
      'ACTIVE' => ExperienceEvidenceLifecycle.active,
      'SUPERSEDED' => ExperienceEvidenceLifecycle.superseded,
      'WITHDRAWN' => ExperienceEvidenceLifecycle.withdrawn,
      _ => ExperienceEvidenceLifecycle.unknown,
    };

String experienceEvidenceLifecycleLabel(String? value) =>
    switch (experienceEvidenceLifecycle(value)) {
      ExperienceEvidenceLifecycle.active => 'Current',
      ExperienceEvidenceLifecycle.superseded => 'Replaced',
      ExperienceEvidenceLifecycle.withdrawn => 'Withdrawn',
      ExperienceEvidenceLifecycle.unknown => 'Status unavailable',
    };

String experienceEvidenceLifecycleExplanation(
  String? value,
) => switch (experienceEvidenceLifecycle(value)) {
  ExperienceEvidenceLifecycle.active =>
    'This evidence is included in the current assessment.',
  ExperienceEvidenceLifecycle.superseded =>
    'This evidence was replaced. It remains in history and is not included in the current assessment.',
  ExperienceEvidenceLifecycle.withdrawn =>
    'This evidence was withdrawn. It remains in history and is not included in the current assessment.',
  ExperienceEvidenceLifecycle.unknown =>
    'The evidence status is unknown, so its use in the current assessment cannot be confirmed.',
};

String experienceEvidenceRelationshipLabel(String? value) =>
    switch (value?.toUpperCase()) {
      'COMPLEMENTARY' => 'Adds supporting information',
      'REPLACEMENT' => 'Replaces an earlier document',
      'CORRECTION' => 'Corrects an earlier document',
      'CONFLICTS_WITH' => 'Differs from another document',
      _ => 'Relationship unavailable',
    };

String experienceEvidenceRelationshipExplanation(String? value) => switch (value
    ?.toUpperCase()) {
  'COMPLEMENTARY' =>
    'Adds context alongside other active evidence; it does not replace it.',
  'REPLACEMENT' =>
    'The server marks the linked earlier document as replaced after this upload succeeds.',
  'CORRECTION' =>
    'The server marks the linked earlier document as replaced after this correction succeeds.',
  'CONFLICTS_WITH' =>
    'Links documents with different information. A conflict alone is not a finding of fraud.',
  _ => 'The relationship details are not available.',
};

String experienceClaimStatusLabel(String? value) => switch (value
    ?.toUpperCase()) {
  'PENDING' => 'Submitted',
  'AWAITING_EVIDENCE' ||
  'NEEDS_INFO' ||
  'ADDITIONAL_EVIDENCE_REQUIRED' ||
  'INSUFFICIENT_EVIDENCE' => 'More evidence needed',
  'PROCESSING' => 'Review in progress',
  'PROCESSING_FAILED' => 'Review could not finish',
  'INCONSISTENT' => 'Conflicting information',
  'VERIFIED' || 'VERIFIED_FROM_EVIDENCE' => 'Supported by submitted evidence',
  'EMPLOYER_CONFIRMED' => 'Employer independently confirmed',
  'REJECTED' => 'Rejected',
  _ => 'Status unavailable',
};

String experienceClaimStatusExplanation(String? value) => switch (value
    ?.toUpperCase()) {
  'VERIFIED' || 'VERIFIED_FROM_EVIDENCE' =>
    'The submitted documents support the claim. This does not guarantee document authenticity or independently confirm the issuer.',
  'EMPLOYER_CONFIRMED' =>
    'An independent verification provider confirmed the claim.',
  'INCONSISTENT' =>
    'Documents differ on some information. This is not by itself a finding of fraud.',
  'PROCESSING' => 'The submitted evidence is being assessed.',
  'PROCESSING_FAILED' =>
    'The assessment did not finish. You can retry it without replacing your evidence.',
  'AWAITING_EVIDENCE' ||
  'NEEDS_INFO' ||
  'ADDITIONAL_EVIDENCE_REQUIRED' ||
  'INSUFFICIENT_EVIDENCE' =>
    'More information is needed before the claim can be assessed.',
  'PENDING' => 'Your claim has been submitted.',
  'REJECTED' => 'The claim was rejected.',
  _ => 'The current claim status is not available.',
};

String experienceAnalysisStatusLabel(String? value) =>
    switch (value?.toUpperCase()) {
      'QUEUED' || 'PROCESSING' => 'Being reviewed',
      'ANALYZED' => 'Analysis complete',
      'INCONCLUSIVE' => 'Inconclusive — not a fraud finding',
      'UNREADABLE' => 'Document could not be read',
      'UNAVAILABLE' || 'FAILED' => 'Analysis unavailable',
      'NOT_RUN' => 'Not started',
      _ => 'Analysis status unavailable',
    };

String experienceFindingOutcomeLabel(String? value) =>
    switch (value?.toUpperCase()) {
      'MATCH' => 'Consistent with the claim',
      'MISMATCH' => 'Differs from the claim',
      'MISSING' => 'Not found in the document',
      'INCONCLUSIVE' => 'Could not be assessed',
      _ => 'Needs review',
    };

ExperienceClaimType experienceClaimType(String? value) =>
    switch (value?.toUpperCase()) {
      'INTERNSHIP' => ExperienceClaimType.internship,
      'MENTORSHIP' => ExperienceClaimType.mentorship,
      'UNSPECIFIED' || null => ExperienceClaimType.unspecified,
      _ => ExperienceClaimType.unspecified,
    };

UserRole userRole(String? value) => switch (value?.toUpperCase()) {
  'STUDENT' => UserRole.student,
  'ALUMNI' => UserRole.alumni,
  'ADMIN' => UserRole.admin,
  _ => UserRole.unknown,
};

class User {
  const User({
    required this.id,
    required this.name,
    required this.email,
    required this.role,
    required this.status,
    this.college,
    this.branch,
    this.passoutYear,
    this.rollno,
    this.section,
    this.bio,
    this.skills,
    this.company,
    this.jobRole,
    this.linkedin,
    this.github,
    this.profileImage,
    this.interests,
    this.location,
  });
  final int id;
  final String name;
  final String email;
  final UserRole role;
  final String status;
  final String? college,
      branch,
      passoutYear,
      rollno,
      section,
      bio,
      skills,
      company,
      jobRole,
      linkedin,
      github,
      profileImage,
      interests,
      location;
  factory User.fromJson(Map<String, dynamic> json) => User(
    id: (json['id'] as num).toInt(),
    name: json['name'] as String? ?? '',
    email: json['email'] as String? ?? '',
    role: userRole(json['role'] as String?),
    status: json['status'] as String? ?? '',
    college: json['college'] as String?,
    branch: json['branch'] as String?,
    passoutYear: json['passoutYear'] as String?,
    rollno: json['rollno'] as String?,
    section: json['section'] as String?,
    bio: json['bio'] as String?,
    skills: json['skills'] as String?,
    company: json['company'] as String?,
    jobRole: json['jobRole'] as String?,
    linkedin: json['linkedin'] as String?,
    github: json['github'] as String?,
    profileImage: json['profileImage'] as String?,
    interests: json['interests'] as String?,
    location: json['location'] as String?,
  );
  Map<String, dynamic> toJson({String? password}) => {
    'name': name,
    'email': email,
    'role': role.name.toUpperCase(),
    'status': status,
    'college': college,
    'branch': branch,
    'passoutYear': passoutYear,
    'rollno': rollno,
    'section': section,
    'bio': bio,
    'skills': skills,
    'company': company,
    'jobRole': jobRole,
    'linkedin': linkedin,
    'github': github,
    'profileImage': profileImage,
    'interests': interests,
    'location': location,
    if (password != null) 'password': password,
  };
}

class EventItem {
  const EventItem({
    required this.id,
    required this.title,
    this.description,
    this.location,
    this.eventDate,
    this.status,
    this.category,
    this.meetingLink,
    this.createdBy,
    this.attendeeCount = 0,
    this.imageUrl,
  });
  final int id;
  final String title;
  final String? description,
      location,
      eventDate,
      status,
      category,
      meetingLink,
      createdBy;
  final int attendeeCount;
  final String? imageUrl;
  Map<String, dynamic> toJson() => {
    'title': title,
    'description': description,
    'location': location,
    'eventDate': eventDate,
    'category': category,
    'meetingLink': meetingLink,
    'imageUrl': imageUrl,
  };
  factory EventItem.fromJson(Map<String, dynamic> j) => EventItem(
    id: (j['id'] as num).toInt(),
    title: j['title'] as String? ?? '',
    description: j['description'] as String?,
    location: j['location'] as String?,
    eventDate: j['eventDate'] as String?,
    status: j['status'] as String?,
    category: j['category'] as String?,
    meetingLink: j['meetingLink'] as String?,
    createdBy: j['createdBy'] as String?,
    attendeeCount: (j['attendeeCount'] as num?)?.toInt() ?? 0,
    imageUrl: j['imageUrl'] as String?,
  );
}

class EventAttendee {
  const EventAttendee({this.id, this.email, this.name, this.registeredAt});
  final int? id;
  final String? email, name, registeredAt;
  factory EventAttendee.fromJson(Map<String, dynamic> j) {
    final user = j['user'] is Map
        ? Map<String, dynamic>.from(j['user'] as Map)
        : <String, dynamic>{};
    return EventAttendee(
      id: (j['id'] as num?)?.toInt(),
      email: j['studentEmail'] as String? ?? user['email'] as String?,
      name: user['name'] as String?,
      registeredAt: j['registeredAt'] as String?,
    );
  }
}

class ConnectionItem {
  const ConnectionItem({
    required this.id,
    required this.status,
    this.requester,
    this.receiver,
  });
  final int id;
  final String status;
  final User? requester, receiver;
  factory ConnectionItem.fromJson(Map<String, dynamic> j) => ConnectionItem(
    id: (j['id'] as num).toInt(),
    status: j['status'] as String? ?? 'PENDING',
    requester: j['requester'] is Map
        ? User.fromJson(Map<String, dynamic>.from(j['requester'] as Map))
        : null,
    receiver: j['receiver'] is Map
        ? User.fromJson(Map<String, dynamic>.from(j['receiver'] as Map))
        : null,
  );
}

class NotificationItem {
  const NotificationItem({
    required this.id,
    required this.message,
    required this.isRead,
    this.type,
    this.timestamp,
  });
  final int id;
  final String message;
  final bool isRead;
  final String? type, timestamp;
  factory NotificationItem.fromJson(Map<String, dynamic> j) => NotificationItem(
    id: (j['id'] as num).toInt(),
    message: j['message'] as String? ?? '',
    isRead: j['read'] as bool? ?? j['isRead'] as bool? ?? false,
    type: j['type'] as String?,
    timestamp: j['timestamp'] as String?,
  );
}

class Conversation {
  const Conversation({
    required this.email,
    required this.latestMessage,
    this.displayName,
    this.profileImage,
    this.participantRole = UserRole.unknown,
    this.timestamp,
  });
  final String email, latestMessage;
  final String? displayName, profileImage;
  final UserRole participantRole;
  final String? timestamp;
  factory Conversation.fromJson(Map<String, dynamic> j) => Conversation(
    email: j['email'] as String? ?? '',
    latestMessage: j['latestMessage'] as String? ?? '',
    displayName: j['displayName'] as String?,
    profileImage: j['profileImage'] as String?,
    participantRole: userRole(j['participantRole'] as String?),
    timestamp: j['timestamp'] as String?,
  );
}

class ChatMessage {
  const ChatMessage({
    this.id,
    required this.senderEmail,
    required this.receiverEmail,
    required this.content,
    this.timestamp,
  });
  final int? id;
  final String senderEmail, receiverEmail, content;
  final String? timestamp;
  factory ChatMessage.fromJson(Map<String, dynamic> j) => ChatMessage(
    id: (j['id'] as num?)?.toInt(),
    senderEmail: j['senderEmail'] as String? ?? '',
    receiverEmail: j['receiverEmail'] as String? ?? '',
    content: j['content'] as String? ?? '',
    timestamp: j['timestamp'] as String?,
  );
}

class ExperienceClaim {
  const ExperienceClaim({
    required this.id,
    required this.company,
    required this.jobTitle,
    required this.summary,
    required this.status,
    required this.aiStatus,
    required this.evidenceAvailable,
    this.claimType = ExperienceClaimType.unspecified,
    this.requestId,
    this.startMonth,
    this.endMonth,
    this.evidenceSummary,
    this.evidenceFileName,
    this.evidenceSize,
    this.aiFindings,
    this.appealNote,
    this.createdAt,
    this.updatedAt,
    this.evidenceVersion = 0,
    this.decisionExplanation,
    this.requestedEvidence,
    this.decisionAt,
    this.corroborationStatus = 'NOT_CHECKED',
    this.analysisMethod = 'NOT_RUN',
    this.ocrStatus = 'NOT_RUN',
  });

  final int id;
  final String company;
  final String jobTitle;
  final String summary;
  final String status;
  final String aiStatus;
  final bool evidenceAvailable;
  final ExperienceClaimType claimType;
  final String? requestId;
  final String? startMonth;
  final String? endMonth;
  final String? evidenceSummary;
  final String? evidenceFileName;
  final int? evidenceSize;
  final String? aiFindings;
  final String? appealNote;
  final String? createdAt;
  final String? updatedAt;
  final int evidenceVersion;
  final String? decisionExplanation;
  final String? requestedEvidence;
  final String? decisionAt;
  final String corroborationStatus;
  final String analysisMethod;
  final String ocrStatus;

  factory ExperienceClaim.fromJson(Map<String, dynamic> json) =>
      ExperienceClaim(
        id: (json['id'] as num).toInt(),
        company: json['company'] as String? ?? '',
        jobTitle: json['jobTitle'] as String? ?? '',
        summary: json['summary'] as String? ?? '',
        status: json['status'] as String? ?? 'UNKNOWN',
        aiStatus: json['aiStatus'] as String? ?? 'UNKNOWN',
        evidenceAvailable: json['evidenceAvailable'] as bool? ?? false,
        claimType: experienceClaimType(json['claimType'] as String?),
        requestId: json['requestId'] as String?,
        startMonth: json['startMonth'] as String?,
        endMonth: json['endMonth'] as String?,
        evidenceSummary: json['evidenceSummary'] as String?,
        evidenceFileName: json['evidenceFileName'] as String?,
        evidenceSize: (json['evidenceSize'] as num?)?.toInt(),
        aiFindings: json['aiFindings'] as String?,
        appealNote: json['appealNote'] as String?,
        createdAt: json['createdAt'] as String?,
        updatedAt: json['updatedAt'] as String?,
        evidenceVersion: (json['evidenceVersion'] as num?)?.toInt() ?? 0,
        decisionExplanation: json['decisionExplanation'] as String?,
        requestedEvidence: json['requestedEvidence'] as String?,
        decisionAt: json['decisionAt'] as String?,
        corroborationStatus:
            json['corroborationStatus'] as String? ?? 'NOT_CHECKED',
        analysisMethod: json['analysisMethod'] as String? ?? 'NOT_RUN',
        ocrStatus: json['ocrStatus'] as String? ?? 'NOT_RUN',
      );
}

class ExperienceClaimAudit {
  const ExperienceClaimAudit({
    required this.eventType,
    required this.createdAt,
    this.oldStatus,
    this.newStatus,
    this.eventNote,
  });

  final String eventType;
  final String createdAt;
  final String? oldStatus;
  final String? newStatus;
  final String? eventNote;

  factory ExperienceClaimAudit.fromJson(Map<String, dynamic> json) =>
      ExperienceClaimAudit(
        eventType: json['eventType'] as String? ?? '',
        createdAt: json['createdAt'] as String? ?? '',
        oldStatus: json['oldStatus'] as String?,
        newStatus: json['newStatus'] as String?,
        eventNote: json['eventNote'] as String?,
      );
}

class ExperienceClaimEvidence {
  const ExperienceClaimEvidence({
    required this.id,
    required this.evidenceVersion,
    required this.evidenceFileName,
    required this.evidenceMediaType,
    required this.evidenceStatus,
    required this.analysisStatus,
    required this.createdAt,
    this.evidenceSize,
    this.evidenceHash,
    this.analysisFindings,
    this.relationshipType,
    this.relatedEvidenceId,
    this.supersededAt,
  });

  final int id;
  final int evidenceVersion;
  final String evidenceFileName;
  final String evidenceMediaType;
  final String evidenceStatus;
  final String analysisStatus;
  final String createdAt;
  final int? evidenceSize;
  final String? evidenceHash;
  final String? analysisFindings;
  final String? relationshipType;
  final int? relatedEvidenceId;
  final String? supersededAt;

  factory ExperienceClaimEvidence.fromJson(Map<String, dynamic> json) =>
      ExperienceClaimEvidence(
        id: (json['id'] as num).toInt(),
        evidenceVersion: (json['evidenceVersion'] as num).toInt(),
        evidenceFileName: json['evidenceFileName'] as String? ?? '',
        evidenceMediaType: json['evidenceMediaType'] as String? ?? '',
        evidenceStatus: json['evidenceStatus'] as String? ?? 'UNKNOWN',
        analysisStatus: json['analysisStatus'] as String? ?? 'UNKNOWN',
        createdAt: json['createdAt'] as String? ?? '',
        evidenceSize: (json['evidenceSize'] as num?)?.toInt(),
        evidenceHash: json['evidenceHash'] as String?,
        analysisFindings: json['analysisFindings'] as String?,
        relationshipType: json['relationshipType'] as String?,
        relatedEvidenceId: (json['relatedEvidenceId'] as num?)?.toInt(),
        supersededAt: json['supersededAt'] as String?,
      );
}
