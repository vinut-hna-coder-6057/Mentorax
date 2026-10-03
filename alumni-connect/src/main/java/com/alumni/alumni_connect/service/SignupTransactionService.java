
package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.SignupRequest;
import com.alumni.alumni_connect.entity.AlumniProfile;
import com.alumni.alumni_connect.entity.StudentProfile;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SignupTransactionService {

    private final UserRepository repository;
    private final BCryptPasswordEncoder encoder;
    private final StudentProfileRepository studentProfileRepository;
    private final AlumniProfileRepository alumniProfileRepository;
    private final OtpService otpService;

    public SignupTransactionService(
            UserRepository repository,
            BCryptPasswordEncoder encoder,
            StudentProfileRepository studentProfileRepository,
            AlumniProfileRepository alumniProfileRepository,
            OtpService otpService
    ) {
        this.repository = repository;
        this.encoder = encoder;
        this.studentProfileRepository = studentProfileRepository;
        this.alumniProfileRepository = alumniProfileRepository;
        this.otpService = otpService;
    }

    public record SignupResult(String email, String otp) {}

    @Transactional
    public SignupResult createAccountAndOtp(SignupRequest request) {
        User user = request.toUser();

        if (repository.findByEmail(user.getEmail()).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Email already exists"
            );
        }

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

        String otp = otpService.generateOtp(
                savedUser.getEmail(),
                "EMAIL_VERIFICATION"
        );

        return new SignupResult(savedUser.getEmail(), otp);
    }
}