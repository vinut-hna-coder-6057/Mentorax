import '../shared/models/models.dart';

const bool emailVerificationEnabled = bool.fromEnvironment(
  'EMAIL_VERIFICATION_ENABLED',
  defaultValue: false,
);

String signupSuccessLocation({
  required UserRole role,
  required String email,
  required bool verificationEnabled,
}) {
  if (verificationEnabled) {
    return Uri(
      path: '/email-verification',
      queryParameters: {'email': email, 'role': role.name},
    ).toString();
  }
  return role == UserRole.alumni ? '/pending-approval' : '/login';
}
