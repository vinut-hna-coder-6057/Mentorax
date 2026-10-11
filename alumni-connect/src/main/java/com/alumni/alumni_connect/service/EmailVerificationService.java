package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.EmailVerificationResponse;
import com.alumni.alumni_connect.dto.VerifyOtpRequest;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.util.EmailAddress;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EmailVerificationService {
    private static final String PURPOSE = "EMAIL_VERIFICATION";
    private static final String GENERIC_RESEND_RESPONSE =
            "If an account requires email verification, a code will be sent shortly";

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final EmailOutboxService emailOutboxService;

    public EmailVerificationService(
            UserRepository userRepository,
            OtpService otpService,
            EmailOutboxService emailOutboxService) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.emailOutboxService = emailOutboxService;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public EmailVerificationResponse verify(VerifyOtpRequest request) {
        String email = EmailAddress.normalize(request.getEmail());
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user == null
                || !isSelfRegisteredRole(user)
                || user.isEmailVerified()
                || !otpService.verifyOtp(email, request.getOtp(), PURPOSE)) {
            throw invalidVerificationCode();
        }

        user.setEmailVerified(true);
        if ("STUDENT".equalsIgnoreCase(user.getRole())
                && "PENDING".equalsIgnoreCase(user.getStatus())) {
            user.setStatus("APPROVED");
        }
        userRepository.save(user);
        otpService.invalidateOtp(email, PURPOSE);
        emailOutboxService.discardOtp(email, PURPOSE);

        boolean approvalPending = "ALUMNI".equalsIgnoreCase(user.getRole())
                && !"APPROVED".equalsIgnoreCase(user.getStatus());
        String message = approvalPending
                ? "Email verified. Your account is awaiting administrator approval."
                : "Email verified. You can now sign in.";
        return new EmailVerificationResponse(true, approvalPending, message);
    }

    @Transactional
    public String resend(String requestedEmail) {
        String email = EmailAddress.normalize(requestedEmail);
        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && isSelfRegisteredRole(user) && !user.isEmailVerified()) {
            String otp = otpService.generateOtp(email, PURPOSE);
            emailOutboxService.enqueueOtp(email, PURPOSE, otp);
        }
        return GENERIC_RESEND_RESPONSE;
    }

    private boolean isSelfRegisteredRole(User user) {
        return "STUDENT".equalsIgnoreCase(user.getRole())
                || "ALUMNI".equalsIgnoreCase(user.getRole());
    }

    private ResponseStatusException invalidVerificationCode() {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid or expired verification code");
    }
}
