import 'dart:typed_data';

import 'package:dio/dio.dart';

import '../core/network/api_client.dart';
import '../shared/models/models.dart';

class EmailVerificationResult {
  const EmailVerificationResult({
    required this.emailVerified,
    required this.approvalPending,
    required this.message,
  });

  final bool emailVerified;
  final bool approvalPending;
  final String message;

  factory EmailVerificationResult.fromJson(Map<String, dynamic> json) =>
      EmailVerificationResult(
        emailVerified: json['emailVerified'] as bool,
        approvalPending: json['approvalPending'] as bool,
        message: json['message'] as String,
      );
}

class AppRepository {
  const AppRepository(this.api);
  Future<Map<String, dynamic>> discoverMentors(String goal) => api.post(
        '/ai/career-assistant',
        data: {'goal': goal},
        decode: (d) => Map<String, dynamic>.from(d as Map),
      );
  Future<Map<String, dynamic>> inboxAssistant(int messageId) => api.post(
        '/messages/$messageId/assistant',
        decode: (d) => Map<String, dynamic>.from(d as Map),
      );
  Future<void> approveAlumni(int id) async {
    await api.put(
      '/alumni/approve/$id',
      decode: (_) {},
    );
  }

  final ApiClient api;
  Future<String> login(String email, String password, UserRole role) =>
      api.post('/login',
          data: {
            'email': email,
            'password': password,
            'role': role.name.toUpperCase()
          },
          decode: (d) => d as String);
  Future<String> signup(User user, String password) => api.post('/signup',
      data: user.toJson(password: password), decode: (d) => d as String);
  Future<EmailVerificationResult> verifyEmail(String email, String otp) =>
      api.post(
        '/verify-email',
        data: {'email': email, 'otp': otp},
        decode: (d) => EmailVerificationResult.fromJson(
          Map<String, dynamic>.from(d as Map),
        ),
      );
  Future<void> resendEmailVerification(String email) =>
      api.post('/resend-verification', data: {'email': email}, decode: (_) {});
  Future<List<User>> users(
    UserRole role, {
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        role == UserRole.alumni ? '/users/alumni' : '/users/students',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) => User.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<User> user(int id) => api.get('/users/$id',
      decode: (d) => User.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<User> userByEmail(String email) => api.get(
        '/users/email/${Uri.encodeComponent(email)}',
        decode: (d) => User.fromJson(Map<String, dynamic>.from(d as Map)),
      );
  Future<User> updateUser(User user) => api.put('/users/${user.id}',
      data: user.toJson(),
      decode: (d) => User.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<List<EventItem>> events({
    bool all = false,
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        all ? '/events/all' : '/events',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) => EventItem.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<void> registerEvent(int id) =>
      api.post('/events/register', query: {'eventId': id}, decode: (_) {});
  Future<void> cancelRegistration(int id) =>
      api.delete('/events/register', query: {'eventId': id}, decode: (_) {});
  Future<bool> eventRegistrationStatus(int id) => api.get(
        '/events/$id/registration-status',
        decode: (d) => (d as Map)['registered'] as bool,
      );
  Future<EventItem> createEvent(EventItem event) => api.post('/events',
      data: event.toJson(),
      decode: (d) => EventItem.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<EventItem> updateEvent(EventItem event) => api.put(
      '/events/${event.id}',
      data: event.toJson(),
      decode: (d) => EventItem.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<void> deleteEvent(int id) => api.delete('/events/$id', decode: (_) {});
  Future<List<EventAttendee>> attendees(
    int id, {
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/events/attendees/$id',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) =>
                EventAttendee.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<List<ConnectionItem>> connections({
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/connections',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) =>
                ConnectionItem.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<ConnectionItem> requestConnection(int id) =>
      api.post('/connections/$id',
          decode: (d) =>
              ConnectionItem.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<ConnectionItem> respondConnection(int id, String status) =>
      api.put('/connections/$id',
          query: {'status': status.trim().toUpperCase()},
          decode: (d) =>
              ConnectionItem.fromJson(Map<String, dynamic>.from(d as Map)));

  Future<List<ExperienceClaim>> experienceClaims({
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/experience-claims/mine',
        query: {'page': page, 'size': size},
        decode: (data) => (data as List)
            .map((item) => ExperienceClaim.fromJson(
                Map<String, dynamic>.from(item as Map)))
            .toList(),
      );

  Future<ExperienceClaim> createExperienceClaim({
    required String company,
    required String jobTitle,
    required String requestId,
    required String summary,
    String? startMonth,
    String? endMonth,
  }) =>
      api.post(
        '/experience-claims',
        data: {
          'company': company,
          'jobTitle': jobTitle,
          'requestId': requestId,
          'summary': summary,
          'startMonth': startMonth,
          'endMonth': endMonth,
        },
        decode: (data) =>
            ExperienceClaim.fromJson(Map<String, dynamic>.from(data as Map)),
      );

  Future<ExperienceClaim> updateExperienceClaim(
    int id, {
    required String company,
    required String jobTitle,
    required String summary,
    required String requestId,
    String? startMonth,
    String? endMonth,
  }) =>
      api.put(
        '/experience-claims/$id',
        data: {
          'company': company,
          'jobTitle': jobTitle,
          'requestId': requestId,
          'summary': summary,
          'startMonth': startMonth,
          'endMonth': endMonth,
        },
        decode: (data) =>
            ExperienceClaim.fromJson(Map<String, dynamic>.from(data as Map)),
      );

  Future<ExperienceClaim> appealExperienceClaim(int id, String note) =>
      api.post(
        '/experience-claims/$id/appeal',
        data: {'note': note},
        decode: (data) =>
            ExperienceClaim.fromJson(Map<String, dynamic>.from(data as Map)),
      );

  Future<List<ExperienceClaimAudit>> experienceClaimHistory(int id) => api.get(
        '/experience-claims/$id/history',
        decode: (data) => (data as List)
            .map((item) => ExperienceClaimAudit.fromJson(
                Map<String, dynamic>.from(item as Map)))
            .toList(),
      );

  Future<ExperienceClaim> uploadExperienceEvidence(
    int id,
    String filename,
    Uint8List bytes, {
    ProgressCallback? onSendProgress,
  }) =>
      api.postForm(
        '/experience-claims/$id/evidence',
        data: FormData.fromMap({
          'file': MultipartFile.fromBytes(
            bytes,
            filename: filename,
            contentType: DioMediaType('application', 'pdf'),
          ),
        }),
        onSendProgress: onSendProgress,
        decode: (data) =>
            ExperienceClaim.fromJson(Map<String, dynamic>.from(data as Map)),
      );

  Future<Uint8List> experienceEvidence(int id) => api.getBytes(
        '/experience-claims/$id/evidence',
        decode: (data) => Uint8List.fromList(List<int>.from(data as List)),
      );

  Future<void> deleteExperienceEvidence(int id) => api.delete(
        '/experience-claims/$id/evidence',
        decode: (_) {},
      );

  Future<List<User>> pendingUsers({
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/alumni/pending',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map(
              (e) => User.fromJson(
                Map<String, dynamic>.from(e as Map),
              ),
            )
            .toList(),
      );

  Future<List<NotificationItem>> notifications({
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/notifications',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) =>
                NotificationItem.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<int> unreadCount() =>
      api.get('/notifications/unread', decode: (d) => (d as num).toInt());
  Future<void> markRead(int id) =>
      api.put('/notifications/read/$id', decode: (_) {});
  Future<List<Conversation>> conversations({
    int page = 0,
    int size = 50,
    UserRole? participantRole,
  }) =>
      api.get(
        '/conversations',
        query: {
          'page': page,
          'size': size,
          if (participantRole != null && participantRole != UserRole.unknown)
            'participantRole': participantRole.name.toUpperCase(),
        },
        decode: (d) => (d as List)
            .map((e) =>
                Conversation.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<List<ChatMessage>> messages(
    String sender,
    String receiver, {
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/messages/conversation',
        query: {
          'sender': sender,
          'receiver': receiver,
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) =>
                ChatMessage.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<void> rejectAlumni(int id) =>
      api.put('/alumni/reject/$id', decode: (_) {});
  Future<void> approveEvent(int id) =>
      api.put('/events/approve/$id', decode: (_) {});
  Future<void> rejectEvent(int id) =>
      api.put('/events/reject/$id', decode: (_) {});
  Future<List<User>> allUsers({
    int page = 0,
    int size = 50,
  }) =>
      api.get(
        '/users',
        query: {
          'page': page,
          'size': size,
        },
        decode: (d) => (d as List)
            .map((e) => User.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
  Future<void> forgotPassword(String email) =>
      api.post('/forgot-password', data: {'email': email}, decode: (_) {});
  Future<String> verifyOtp(String email, String otp) => api.post(
        '/verify-otp',
        data: {'email': email, 'otp': otp},
        decode: (d) => (d as Map)['resetToken'] as String,
      );
  Future<void> resetPassword(
          String email, String password, String resetToken) =>
      api.post('/reset-password',
          data: {
            'email': email,
            'newPassword': password,
            'resetToken': resetToken,
          },
          decode: (_) {});
}
