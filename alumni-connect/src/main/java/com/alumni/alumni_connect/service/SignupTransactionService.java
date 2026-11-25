
package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.SignupRequest;
import com.alumni.alumni_connect.entity.AlumniProfile;
import com.alumni.alumni_connect.entity.StudentProfile;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignupTransactionService {

    private final UserRepository repository;
    private final PasswordEncoder encoder;
    private final StudentProfileRepository studentProfileRepository;
    private final AlumniProfileRepository alumniProfileRepository;
    private final OtpService otpService;
    private final EmailOutboxService emailOutboxService;
    @Value("${app.auth.email-verification-required:true}")
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

    @Transactional
    public void createAccount(SignupRequest request) {
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
            throw emailAlreadyExists();
        }

        user.setPassword(encoder.encode(user.getPassword()));
        // The temporary mode skips verification without claiming that the email was verified.
        user.setEmailVerified(false);
        user.setStatus(!emailVerificationRequired && "STUDENT".equalsIgnoreCase(user.getRole())
                ? "APPROVED"
                : "PENDING");

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
            String otp = otpService.generateOtp(savedUser.getEmail(), "EMAIL_VERIFICATION");
            emailOutboxService.enqueueOtp(savedUser.getEmail(), "EMAIL_VERIFICATION", otp);
        }
    }

    private ResponseStatusException emailAlreadyExists() {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Email already exists.");
    }
}
