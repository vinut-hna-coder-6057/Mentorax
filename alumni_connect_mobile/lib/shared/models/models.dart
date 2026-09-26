enum UserRole { student, alumni, admin, unknown }

UserRole userRole(String? value) => switch (value?.toUpperCase()) {
      'STUDENT' => UserRole.student,
      'ALUMNI' => UserRole.alumni,
      'ADMIN' => UserRole.admin,
      _ => UserRole.unknown
    };

class User {
  const User(
      {required this.id,
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
      this.location});
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
      location: json['location'] as String?);
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
        if (password != null) 'password': password
      };
}

class EventItem {
  const EventItem(
      {required this.id,
      required this.title,
      this.description,
      this.location,
      this.eventDate,
      this.status,
      this.category,
      this.meetingLink,
      this.createdBy,
      this.attendeeCount = 0,
      this.imageUrl});
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
      imageUrl: j['imageUrl'] as String?);
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
  const ConnectionItem(
      {required this.id, required this.status, this.requester, this.receiver});
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
          : null);
}

class NotificationItem {
  const NotificationItem(
      {required this.id,
      required this.message,
      required this.isRead,
      this.type,
      this.timestamp});
  final int id;
  final String message;
  final bool isRead;
  final String? type, timestamp;
  factory NotificationItem.fromJson(Map<String, dynamic> j) => NotificationItem(
      id: (j['id'] as num).toInt(),
      message: j['message'] as String? ?? '',
      isRead: j['read'] as bool? ?? j['isRead'] as bool? ?? false,
      type: j['type'] as String?,
      timestamp: j['timestamp'] as String?);
}

class Conversation {
  const Conversation(
      {required this.email, required this.latestMessage, this.timestamp});
  final String email, latestMessage;
  final String? timestamp;
  factory Conversation.fromJson(Map<String, dynamic> j) => Conversation(
      email: j['email'] as String? ?? '',
      latestMessage: j['latestMessage'] as String? ?? '',
      timestamp: j['timestamp'] as String?);
}

class ChatMessage {
  const ChatMessage(
      {this.id,
      required this.senderEmail,
      required this.receiverEmail,
      required this.content,
      this.timestamp});
  final int? id;
  final String senderEmail, receiverEmail, content;
  final String? timestamp;
  factory ChatMessage.fromJson(Map<String, dynamic> j) => ChatMessage(
      id: (j['id'] as num?)?.toInt(),
      senderEmail: j['senderEmail'] as String? ?? '',
      receiverEmail: j['receiverEmail'] as String? ?? '',
      content: j['content'] as String? ?? '',
      timestamp: j['timestamp'] as String?);
}
