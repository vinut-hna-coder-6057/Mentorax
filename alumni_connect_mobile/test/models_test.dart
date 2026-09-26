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
}
