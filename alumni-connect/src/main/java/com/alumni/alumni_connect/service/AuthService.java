package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.SignupRequest;
import com.alumni.alumni_connect.dto.LoginRequest;
import com.alumni.alumni_connect.entity.AlumniProfile;
import com.alumni.alumni_connect.entity.StudentProfile;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.AlumniProfileRepository;
import com.alumni.alumni_connect.repository.StudentProfileRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserRepository repository;
    private final BCryptPasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final StudentProfileRepository studentProfileRepository;
    private final AlumniProfileRepository alumniProfileRepository;
    private final OtpService otpService;
    private final EmailService emailService;

    public AuthService(
            UserRepository repository,
            BCryptPasswordEncoder encoder,
            JwtUtil jwtUtil,
            StudentProfileRepository studentProfileRepository,
            AlumniProfileRepository alumniProfileRepository,
            OtpService otpService,
            EmailService emailService
    ) {
        this.repository = repository;
        this.encoder = encoder;
        this.jwtUtil = jwtUtil;
        this.studentProfileRepository = studentProfileRepository;
        this.alumniProfileRepository = alumniProfileRepository;
        this.otpService = otpService;
        this.emailService = emailService;
    }

    // =====================================
    // SIGNUP
    // =====================================

    @Transactional
    public String signup(SignupRequest request) {
        return signup(request.toUser());
    }

    /** Legacy service entry point retained for existing internal callers; web input uses SignupRequest. */
    public String signup(User user) {

        Optional<User> existing =
                repository.findByEmail(user.getEmail());

        if (existing.isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Email already exists"
            );
        }

        // Only STUDENT and ALUMNI can self-register.
        if (!"STUDENT".equalsIgnoreCase(user.getRole())
                && !"ALUMNI".equalsIgnoreCase(user.getRole())) {

            throw new IllegalArgumentException(
                    "Only STUDENT and ALUMNI self-registration is allowed"
            );
        }

        // Validate password before encoding.
        if (user.getPassword() == null
                || user.getPassword().length() < 8
                || user.getPassword().length() > 128) {

            throw new IllegalArgumentException(
                    "Password does not meet requirements"
            );
        }

        // Password must never be stored in plaintext.
        user.setPassword(
                encoder.encode(user.getPassword())
        );

        // New accounts must verify their email first.
        user.setEmailVerified(false);

        /*
         * STUDENT:
         * Email verification is the required verification step.
         *
         * ALUMNI:
         * Email verification happens first, followed by
         * administrator approval.
         */
        if ("ALUMNI".equalsIgnoreCase(user.getRole())) {
            user.setStatus("PENDING");
        } else {
            user.setStatus("APPROVED");
        }

        // Save the user first so profiles can reference it.
        User savedUser = repository.save(user);

        // Create role-specific profile.
        if ("STUDENT".equalsIgnoreCase(savedUser.getRole())) {

            StudentProfile profile =
                    new StudentProfile(savedUser);

            profile.copyLegacyFields(savedUser);

            studentProfileRepository.save(profile);

        } else {

            AlumniProfile profile =
                    new AlumniProfile(savedUser);

            profile.copyLegacyFields(savedUser);

            alumniProfileRepository.save(profile);
        }

        // Generate an email-verification OTP.
        String otp = otpService.generateOtp(
                savedUser.getEmail(),
                "EMAIL_VERIFICATION"
        );

        // Send OTP to the registered email address.
        emailService.sendEmailVerificationOtp(
                savedUser.getEmail(),
                otp
        );

        return "Signup successful";
    }

    // =====================================
    // LOGIN
    // =====================================

    public Object login(LoginRequest request) {
        return login(request.email(), request.password());
    }

    /** Legacy service entry point retained for existing internal callers; web input uses LoginRequest. */
    public Object login(User user) { return login(user.getEmail(), user.getPassword()); }

    private Object login(String email, String password) {
       Optional<User> optionalUser =
        repository.findByEmail(
                email
        );

        // User does not exist.
        if (optionalUser.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid credentials"
            );
        }

        User existing = optionalUser.get();

        // Always verify password before revealing account state.
        if (!encoder.matches(
                password,
                existing.getPassword()
        )) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid credentials"
            );
        }

        // Email must be verified before login.
        if (!existing.isEmailVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Email verification required"
            );
        }

        // Rejected account.
        if ("REJECTED".equalsIgnoreCase(existing.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account rejected"
            );
        }

        // Alumni waiting for administrator approval.
        if ("PENDING".equalsIgnoreCase(existing.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account pending approval"
            );
        }

        // Any unsupported/inactive state.
        if (!"APPROVED".equalsIgnoreCase(existing.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account not active"
            );
        }

        // Approved and verified user.
        return jwtUtil.generateToken(
                existing.getEmail(),
                existing.getRole()
        );
    }

    // =====================================
    // APPROVE USER
    // =====================================
@PreAuthorize("hasRole('ADMIN')")
@Transactional
public User approveUser(Long id) {

    User user = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "User not found"
            ));

    user.setStatus("APPROVED");
    User saved = repository.save(user);
    if ("ALUMNI".equalsIgnoreCase(saved.getRole())) {
        AlumniProfile profile = alumniProfileRepository.findById(saved.getId())
                .orElseGet(() -> {
                    AlumniProfile created = new AlumniProfile(saved);
                    created.copyLegacyFields(saved);
                    return created;
                });
        profile.setApprovalStatus(saved.getStatus());
        profile.setApprovedAt(LocalDateTime.now());
        alumniProfileRepository.save(profile);
    }
    return saved;
}
}
