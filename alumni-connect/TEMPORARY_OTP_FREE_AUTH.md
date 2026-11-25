# Temporary OTP-free authentication

This mode is intended only for temporary product testing while the email sender
is unavailable. It removes email verification as a prerequisite for self-signup
and password login; it does not remove password checks, password hashing, role
checks, account rejection checks, or JWT authentication.

## Enable for the current Render service

Add this environment variable to the backend service:

```text
AUTH_EMAIL_VERIFICATION_REQUIRED=false
```

Save the setting and deploy the application build that contains this feature.
The application property defaults to `true`, so removing the variable restores
the existing OTP-required behavior. This repository change does not alter
Render configuration or deploy the service.

## Behavior while disabled

- Student and alumni signup still validates the existing fields, stores a
  BCrypt password hash, and creates the corresponding profile.
- Signup does not generate an email-verification OTP or enqueue an OTP email.
  The stored `emailVerified` value remains false; the application does not
  falsely claim that the address was verified.
- While disabled, login, the HTTP JWT filter, and WebSocket authentication do
  not require that verification flag. They still require a valid password/JWT,
  matching role, and an approved, non-rejected account.
- New student accounts are approved at signup. Existing unverified students in
  the legacy `PENDING` email-verification state are moved to `APPROVED` only
  after a correct password is checked at login.
- Alumni accounts remain `PENDING` until the existing administrator approval
  flow approves them. Disabling email verification does not bypass that
  approval.
- Existing password hashes and account data are not reset or migrated.
- A missing, blank, or malformed password hash cannot authenticate. A user
  with no usable password must use an existing approved password-reset process
  or have their identity verified and password reset through an authorized
  administrative process. The existing password-reset OTP flow is unchanged.
- Email verification and OTP endpoints remain available for compatibility and
  other flows; email verification is still required when this setting is true.

Because signup no longer proves control of an email address, disable this mode
after testing. Do not use it as a permanent registration policy.
