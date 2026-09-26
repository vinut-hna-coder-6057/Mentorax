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
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface UserRepository
        extends JpaRepository<User, Long> {

    // =========================================
    // FIND BY EMAIL
    // =========================================

    Optional<User> findByEmail(

            String email
    );

    // =========================================
    // FIND BY EMAIL + ROLE
    // =========================================

    Optional<User> findByEmailAndRole(

            String email,

            String role
    );

    // =========================================
    // FIND BY ROLE
    // =========================================

    List<User> findByRole(

            String role
    );

    // =========================================
    // FIND BY STATUS
    // =========================================

    List<User> findByStatus(

            String status
    );

    // =========================================
    // FIND BY ROLE + STATUS
    // =========================================

    List<User> findByRoleAndStatus(

            String role,

            String status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<User> findByIdInOrderByIdAsc(List<Long> ids);
}
