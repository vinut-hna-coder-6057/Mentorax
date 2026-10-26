enum InboxCategory {
  allMessages,
  referralRequests,
  careerAdvice,
  mentorship,
  jobOpportunities,
  interviewPreparation,
  other,
}

extension InboxCategoryLabel on InboxCategory {
  String get label => switch (this) {
        InboxCategory.allMessages => 'All Messages',
        InboxCategory.referralRequests => 'Referral Requests',
        InboxCategory.careerAdvice => 'Career Advice',
        InboxCategory.mentorship => 'Mentorship',
        InboxCategory.jobOpportunities => 'Job Opportunities',
        InboxCategory.interviewPreparation => 'Interview Preparation',
        InboxCategory.other => 'Other',
      };
}

InboxCategory classifyConversationMessages(Iterable<String> messages) {
  final distinctMessages = <String>{};
  final meaningfulMessages = <String>[];
  for (final message in messages) {
    final normalized = message
        .toLowerCase()
        .replaceAll(RegExp(r'[^a-z0-9]+'), ' ')
        .trim();
    if (normalized.isNotEmpty &&
        !_isGreetingOrAcknowledgement(normalized) &&
        distinctMessages.add(normalized)) {
      meaningfulMessages.add(normalized);
    }
  }
  final text = meaningfulMessages.join(' ');
  if (text.isEmpty) return InboxCategory.other;

  if (RegExp(
    r'\breferrals?\b|\brefer(?:red|ring)? me\b|\bemployee referrals?\b|'
    r'\binternal referrals?\b|\bput in (?:a )?referrals?\b|'
    r'\bapply(?:ing)? through (?:a )?referrals?\b',
  ).hasMatch(text)) {
    return InboxCategory.referralRequests;
  }
  if (RegExp(
    r'\binterview\b|\bmock interview\b|\bcoding round\b|\btechnical round\b',
  ).hasMatch(text)) {
    return InboxCategory.interviewPreparation;
  }
  if (RegExp(r'\bmentor(?:ship|ing)?\b').hasMatch(text)) {
    return InboxCategory.mentorship;
  }
  if (RegExp(
    r'\bcareer\b|\bprofessional growth\b|\bjob search\b|\bjob hunting\b|'
    r'\bresume review\b|\bcareer path\b|\bcareer growth\b',
  ).hasMatch(text)) {
    return InboxCategory.careerAdvice;
  }
  if (RegExp(
    r'\bjob opportunit(?:y|ies)\b|\binternship opportunit(?:y|ies)\b|'
    r'\bopenings?\b|\bvacanc(?:y|ies)\b|\bhiring\b|\brecruit(?:ing|ment)\b|'
    r'\bposition available\b|\brole available\b',
  ).hasMatch(text)) {
    return InboxCategory.jobOpportunities;
  }
  return InboxCategory.other;
}

bool _isGreetingOrAcknowledgement(String text) => RegExp(
      r'^(?:hi|hello|hey|hii+|good morning|good afternoon|good evening|'
      r'how are you(?: doing)?|thanks|thank you|ok|okay|got it|sure|cool|'
      r'great|yes|no|welcome)(?: [a-z]{2,})?$',
    ).hasMatch(text);
