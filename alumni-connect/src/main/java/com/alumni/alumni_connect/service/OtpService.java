package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.security.SecureRandom;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtpService {

    private final OtpRepository otpRepository;

        private final PasswordEncoder passwordEncoder;

    public OtpService(
            OtpRepository otpRepository,
            PasswordEncoder passwordEncoder
    ) {

        this.otpRepository =
                otpRepository;

        this.passwordEncoder = passwordEncoder;
    }

    // =====================================
    // GENERATE OTP
    // =====================================

    @Transactional
    public String generateOtp(String email, String purpose) {

        SecureRandom random = new SecureRandom();

        int number =
                100000 + random.nextInt(900000);

        String otpValue =
                String.valueOf(number);

        // REMOVE OLD OTP

      otpRepository.deleteByEmailAndPurpose(email, purpose);

        // CREATE NEW OTP

        Otp otp =
                new Otp();

        otp.setEmail(email);

        otp.setPurpose(purpose);

        otp.setCodeHash(passwordEncoder.encode(otpValue));

        otp.setExpiry(
                LocalDateTime.now()
                        .plusMinutes(5)
        );

        otp.setVerified(false);
        otp.setAttemptCount(0);

        otpRepository.save(otp);

        return otpValue;
    }

    // =====================================
    // VERIFY OTP
    // =====================================

    @Transactional
    public boolean verifyOtp(
            String email,
            String otpValue,
            String purpose
    ) {

        Otp otp =
                otpRepository
                .findFirstByEmailAndPurposeOrderByIdDesc(email, purpose)
                        .orElse(null);

        // OTP NOT FOUND

        if (otp == null) {

            return false;
        }

                if (otp.isVerified() || otp.getAttemptCount() >= 5) {
                        return false;
                }

        // CHECK EXPIRY

        if (
                otp.getExpiry()
                        .isBefore(
                                LocalDateTime.now()
                        )
        ) {

            otp.setConsumedAt(LocalDateTime.now());
            otpRepository.save(otp);

            return false;
        }

        // CHECK OTP

                if (!passwordEncoder.matches(otpValue, otp.getCodeHash())) {
                        otp.setAttemptCount(otp.getAttemptCount() + 1);
                        otpRepository.save(otp);

            return false;
        }

        // MARK VERIFIED

        otp.setVerified(true);
        otp.setConsumedAt(LocalDateTime.now());

        otpRepository.save(otp);

        return true;
    }
}

