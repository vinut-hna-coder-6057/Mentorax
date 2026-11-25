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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.time.LocalDateTime;

@Service
public class AuthService {
    private static final String VERIFICATION_REQUIRED_SIGNUP_RESPONSE =
            "If this address is eligible, verification instructions will be sent shortly.";
    private static final String OTP_FREE_SIGNUP_RESPONSE =
            "If this address is eligible, you can sign in with your email and password.";

    private final UserRepository repository;
    private final BCryptPasswordEncoder encoder;
    private final JwtUtil jwtUtil;
    private final AlumniProfileRepository alumniProfileRepository;
    private final SignupTransactionService signupTransactionService;
    @Value("${app.auth.email-verification-required:true}")
    private boolean emailVerificationRequired = true;
    public AuthService(
        UserRepository repository,
        BCryptPasswordEncoder encoder,
        JwtUtil jwtUtil,
        AlumniProfileRepository alumniProfileRepository,
        SignupTransactionService signupTransactionService
) {
    this.repository = repository;
    this.encoder = encoder;
    this.jwtUtil = jwtUtil;
    this.alumniProfileRepository = alumniProfileRepository;
    this.signupTransactionService = signupTransactionService;
}
    // =====================================
    // SIGNUP
    // =====================================
public String signup(SignupRequest request) {
    String signupResponse = emailVerificationRequired
            ? VERIFICATION_REQUIRED_SIGNUP_RESPONSE
            : OTP_FREE_SIGNUP_RESPONSE;
    try {
        signupTransactionService.createAccount(request);
} catch (ResponseStatusException exception) {
    if (exception.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
        return signupResponse;
    }
    throw exception;
} catch (DataIntegrityViolationException exception) {
    if (repository.findByEmailIgnoreCase(request.email()).isPresent()) {
        return signupResponse;
    }
    throw exception;
}

return signupResponse;
}

/** Legacy entry point retained for existing internal callers. */
public String signup(User user) {
    if (user == null || (!"STUDENT".equalsIgnoreCase(user.getRole())
            && !"ALUMNI".equalsIgnoreCase(user.getRole()))) {
        throw new IllegalArgumentException(
                "Only STUDENT and ALUMNI self-registration is allowed"
        );
    }

    return signup(new SignupRequest(
            user.getName(),
            user.getEmail(),
            user.getPassword(),
            user.getRole(),
            user.getCollege(),
            user.getBranch(),
            user.getPassoutYear(),
            user.getRollno(),
            user.getSection(),
            user.getBio(),
            user.getSkills(),
            user.getCompany(),
            user.getJobRole(),
            user.getLinkedin(),
            user.getGithub(),
            user.getProfileImage(),
            user.getInterests(),
            user.getLocation()));
}
    @Transactional
    public Object login(LoginRequest request) {
        return login(request.email(), request.password(), request.role());
    }

    /** Legacy service entry point retained for existing internal callers; web input uses LoginRequest. */
    @Transactional
    public Object login(User user) { return login(user.getEmail(), user.getPassword(), user.getRole()); }

    private Object login(String email, String password, String requestedRole) {
       Optional<User> optionalUser =
        repository.findByEmailIgnoreCase(
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
        String passwordHash = existing.getPassword();
        boolean passwordMatches = false;
        if (password != null && !password.isBlank() && passwordHash != null && !passwordHash.isBlank()) {
            try {
                passwordMatches = encoder.matches(password, passwordHash);
            } catch (IllegalArgumentException ignored) {
                // Treat malformed stored hashes as invalid credentials; never bypass password checks.
            }
        }
        if (!passwordMatches) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid credentials"
            );
        }

        if (requestedRole != null
                && (existing.getRole() == null
                || !requestedRole.equalsIgnoreCase(existing.getRole()))) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email, password, or account type"
            );
        }

        // Rejected account.
        if ("REJECTED".equalsIgnoreCase(existing.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Account rejected"
            );
        }

        if (emailVerificationRequired
                && ("STUDENT".equalsIgnoreCase(existing.getRole())
                || "ALUMNI".equalsIgnoreCase(existing.getRole()))
                && !existing.isEmailVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Email verification required"
            );
        }

        // Student PENDING status historically represented waiting for email OTP.
        // In temporary OTP-free mode, release only that exact state after password verification.
        if (!emailVerificationRequired
                && "STUDENT".equalsIgnoreCase(existing.getRole())
                && "PENDING".equalsIgnoreCase(existing.getStatus())
                && !existing.isEmailVerified()) {
            existing.setStatus("APPROVED");
            repository.save(existing);
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

        // Approved user.
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
