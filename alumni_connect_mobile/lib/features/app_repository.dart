import '../core/network/api_client.dart';
import '../shared/models/models.dart';

class AppRepository {
  const AppRepository(this.api);
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
          query: {'status': status},
          decode: (d) =>
              ConnectionItem.fromJson(Map<String, dynamic>.from(d as Map)));

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
  }) =>
      api.get(
        '/conversations',
        query: {'page': page, 'size': size},
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
  Future<String> verifyOtp(String email, String otp, String purpose) =>
      api.post(
        '/verify-otp',
        data: {'email': email, 'otp': otp},
        decode: (d) => (d as Map)['resetToken'] as String,
      );
  Future<void> verifyEmail(String email, String otp) => api.post(
        '/verify-email',
        data: {
          'email': email,
          'otp': otp,
        },
        decode: (_) {},
      );
  Future<void> resendVerification(String email) => api.post(
        '/resend-verification',
        data: {'email': email},
        decode: (_) {},
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
