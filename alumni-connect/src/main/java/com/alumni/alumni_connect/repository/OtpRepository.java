package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpRepository
        extends JpaRepository<Otp, Long> {

    Optional<Otp> findFirstByEmailAndPurposeOrderByIdDesc(String email, String purpose);

    Optional<Otp> findFirstByEmailAndPurposeAndVerifiedTrueAndConsumedAtIsNotNullOrderByIdDesc(
            String email, String purpose);

    void deleteByEmailAndPurpose(String email, String purpose);
}

