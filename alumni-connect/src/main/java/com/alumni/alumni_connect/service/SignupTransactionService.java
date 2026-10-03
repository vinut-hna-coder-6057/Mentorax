
package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.SignupRequest;
import com.alumni.alumni_connect.entity.AlumniProfile;
import com.alumni.alumni_connect.entity.StudentProfile;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignupTransactionService {

    private final UserRepository repository;
    private final PasswordEncoder encoder;
    private final StudentProfileRepository studentProfileRepository;
    private final AlumniProfileRepository alumniProfileRepository;
    private final OtpService otpService;
    private final EmailOutboxService emailOutboxService;
    @Value("${app.email-verification.required:false}")
    private boolean emailVerificationRequired = true;

    public SignupTransactionService(
            UserRepository repository,
            PasswordEncoder encoder,
            StudentProfileRepository studentProfileRepository,
            AlumniProfileRepository alumniProfileRepository,
            OtpService otpService,
            EmailOutboxService emailOutboxService
    ) {
        this.repository = repository;
        this.encoder = encoder;
        this.studentProfileRepository = studentProfileRepository;
        this.alumniProfileRepository = alumniProfileRepository;
        this.otpService = otpService;
        this.emailOutboxService = emailOutboxService;
    }

    public record SignupResult(String email) {}

    @Transactional
    public SignupResult createAccountAndOtp(SignupRequest request) {
        User user = request.toUser();

        if (!"STUDENT".equalsIgnoreCase(user.getRole())
                && !"ALUMNI".equalsIgnoreCase(user.getRole())) {
            throw new IllegalArgumentException(
                    "Only STUDENT and ALUMNI self-registration is allowed"
            );
        }

        if (user.getPassword() == null
                || user.getPassword().length() < 8
                || user.getPassword().length() > 128) {
            throw new IllegalArgumentException(
                    "Password does not meet requirements"
            );
        }

        User existing = repository.findByEmailIgnoreCase(user.getEmail()).orElse(null);
        if (existing != null) {
            if (existing.isEmailVerified()
                    || existing.getRole() == null
                    || !existing.getRole().equalsIgnoreCase(user.getRole())
                    || !encoder.matches(user.getPassword(), existing.getPassword())) {
                throw emailAlreadyExists();
            }

            if (emailVerificationRequired) {
                String retryOtp = otpService.generateOtp(
                        existing.getEmail(),
                        "EMAIL_VERIFICATION");
                emailOutboxService.enqueueVerification(existing.getEmail(), retryOtp);
            }
            return new SignupResult(existing.getEmail());
        }

        user.setPassword(encoder.encode(user.getPassword()));
        user.setEmailVerified(false);

        if ("ALUMNI".equalsIgnoreCase(user.getRole())) {
            user.setStatus("PENDING");
        } else {
            user.setStatus("APPROVED");
        }

        User savedUser = repository.save(user);

        if ("STUDENT".equalsIgnoreCase(savedUser.getRole())) {
            StudentProfile profile = new StudentProfile(savedUser);
            profile.copyLegacyFields(savedUser);
            studentProfileRepository.save(profile);
        } else {
            AlumniProfile profile = new AlumniProfile(savedUser);
            profile.copyLegacyFields(savedUser);
            alumniProfileRepository.save(profile);
        }

        if (emailVerificationRequired) {
            String otp = otpService.generateOtp(
                    savedUser.getEmail(),
                    "EMAIL_VERIFICATION"
            );
            emailOutboxService.enqueueVerification(savedUser.getEmail(), otp);
        }

        return new SignupResult(savedUser.getEmail());
    }

    private ResponseStatusException emailAlreadyExists() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Email already exists. Use the original account details to resume verification.");
    }
}