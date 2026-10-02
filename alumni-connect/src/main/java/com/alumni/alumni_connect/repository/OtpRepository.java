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
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

import java.util.Optional;
import jakarta.persistence.LockModeType;

public interface OtpRepository
        extends JpaRepository<Otp, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Otp> findFirstByEmailAndPurposeOrderByIdDesc(String email, String purpose);

    Optional<Otp> findFirstByEmailAndPurposeAndVerifiedTrueAndConsumedAtIsNotNullOrderByIdDesc(
            String email, String purpose);

    void deleteByEmailAndPurpose(String email, String purpose);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Otp o SET o.resetTokenHash = null, o.resetTokenExpiry = null "
            + "WHERE o.email = :email AND o.resetTokenHash = :tokenHash "
            + "AND o.resetTokenExpiry > :now")
    int consumeResetAuthorization(
            @Param("email") String email,
            @Param("tokenHash") String tokenHash,
            @Param("now") LocalDateTime now);
}

