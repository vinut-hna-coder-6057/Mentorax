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
import org.springframework.dao.DataIntegrityViolationException;

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
        private final SignupTransactionService signupTransactionService;
    public AuthService(
        UserRepository repository,
        BCryptPasswordEncoder encoder,
        JwtUtil jwtUtil,
        StudentProfileRepository studentProfileRepository,
        AlumniProfileRepository alumniProfileRepository,
        OtpService otpService,
        EmailService emailService,
        SignupTransactionService signupTransactionService
) {
    this.repository = repository;
    this.encoder = encoder;
    this.jwtUtil = jwtUtil;
    this.studentProfileRepository = studentProfileRepository;
    this.alumniProfileRepository = alumniProfileRepository;
    this.otpService = otpService;
    this.emailService = emailService;
    this.signupTransactionService = signupTransactionService;
}
    // =====================================
    // SIGNUP
    // =====================================
public String signup(SignupRequest request) {
    SignupTransactionService.SignupResult result;
    try {
        result = signupTransactionService.createAccountAndOtp(request);
    } catch (DataIntegrityViolationException exception) {
        if (repository.findByEmail(request.email()).isEmpty()) {
            throw exception;
        }
        result = signupTransactionService.createAccountAndOtp(request);
    }

    // The database transaction has committed before this email is sent.
    emailService.sendEmailVerificationOtp(
            result.email(),
            result.otp()
    );

    return "Signup successful";
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
    public Object login(LoginRequest request) {
        return login(request.email(), request.password(), request.role());
    }

    /** Legacy service entry point retained for existing internal callers; web input uses LoginRequest. */
    public Object login(User user) { return login(user.getEmail(), user.getPassword(), user.getRole()); }

    private Object login(String email, String password, String requestedRole) {
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

        if (requestedRole != null
                && (existing.getRole() == null
                || !requestedRole.equalsIgnoreCase(existing.getRole()))) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email, password, or account type"
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
