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
  Future<List<User>> users(UserRole role) =>
      api.get(role == UserRole.alumni ? '/users/alumni' : '/users/students',
          decode: (d) => (d as List)
              .map((e) => User.fromJson(Map<String, dynamic>.from(e as Map)))
              .toList());
  Future<User> user(int id) => api.get('/users/$id',
      decode: (d) => User.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<User> userByEmail(String email) => api.get(
        '/users/email/${Uri.encodeComponent(email)}',
        decode: (d) => User.fromJson(Map<String, dynamic>.from(d as Map)),
      );
  Future<User> updateUser(User user) => api.put('/users/${user.id}',
      data: user.toJson(),
      decode: (d) => User.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<List<EventItem>> events({bool all = false}) => api.get(
      all ? '/events/all' : '/events',
      decode: (d) => (d as List)
          .map((e) => EventItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<void> registerEvent(int id) =>
      api.post('/events/register', query: {'eventId': id}, decode: (_) {});
  Future<void> cancelRegistration(int id) =>
      api.delete('/events/register', query: {'eventId': id}, decode: (_) {});
  Future<EventItem> createEvent(EventItem event) => api.post('/events',
      data: event.toJson(),
      decode: (d) => EventItem.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<EventItem> updateEvent(EventItem event) => api.put('/events/${event.id}',
      data: event.toJson(),
      decode: (d) => EventItem.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<void> deleteEvent(int id) =>
      api.delete('/events/$id', decode: (_) {});
  Future<List<EventAttendee>> attendees(int id) => api.get(
      '/events/attendees/$id',
      decode: (d) => (d as List)
          .map((e) =>
              EventAttendee.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<List<ConnectionItem>> connections() => api.get('/connections',
      decode: (d) => (d as List)
          .map((e) =>
              ConnectionItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<ConnectionItem> requestConnection(int id) =>
      api.post('/connections/$id',
          decode: (d) =>
              ConnectionItem.fromJson(Map<String, dynamic>.from(d as Map)));
  Future<ConnectionItem> respondConnection(int id, String status) =>
      api.put('/connections/$id',
          query: {'status': status},
          decode: (d) =>
              ConnectionItem.fromJson(Map<String, dynamic>.from(d as Map)));
             
               Future<List<User>> pendingUsers() => api.get(
      '/alumni/pending',
      decode: (d) => (d as List)
          .map(
            (e) => User.fromJson(
              Map<String, dynamic>.from(e as Map),
            ),
          )
          .toList(),
    );
  Future<List<NotificationItem>> notifications() => api.get('/notifications',
      decode: (d) => (d as List)
          .map((e) =>
              NotificationItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<int> unreadCount() =>
      api.get('/notifications/unread', decode: (d) => (d as num).toInt());
  Future<void> markRead(int id) =>
      api.put('/notifications/read/$id', decode: (_) {});
  Future<List<Conversation>> conversations() => api.get('/conversations',
      decode: (d) => (d as List)
          .map(
              (e) => Conversation.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<List<ChatMessage>> messages(String sender, String receiver) => api.get(
      '/messages/conversation',
      query: {'sender': sender, 'receiver': receiver},
      decode: (d) => (d as List)
          .map((e) => ChatMessage.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<void> rejectAlumni(int id) =>
    api.put('/alumni/reject/$id', decode: (_) {});
  Future<void> approveEvent(int id) =>
      api.put('/events/approve/$id', decode: (_) {});
  Future<void> rejectEvent(int id) =>
      api.put('/events/reject/$id', decode: (_) {});
  Future<List<User>> allUsers() => api.get('/users',
      decode: (d) => (d as List)
          .map((e) => User.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList());
  Future<void> forgotPassword(String email) =>
      api.post('/forgot-password', data: {'email': email}, decode: (_) {});
  Future<void> verifyOtp(String email, String otp, String purpose) => api.post('/verify-otp',
      data: {'email': email, 'otp': otp}, decode: (_) {});
  Future<void> verifyEmail(String email, String otp) =>
    api.post(
      '/verify-email',
      data: {
        'email': email,
        'otp': otp,
      },
      decode: (_) {},
    );
  Future<void> resetPassword(String email, String password) =>
      api.post('/reset-password',
          data: {'email': email, 'newPassword': password}, decode: (_) {});
}
