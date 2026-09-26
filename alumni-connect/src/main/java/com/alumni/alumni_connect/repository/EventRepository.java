package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EventRepository
        extends JpaRepository<Event, Long> {

    // =====================================
    // ALL EVENTS
    // =====================================

    List<Event>
    findAllByOrderByCreatedAtDesc();

    // =====================================
    // APPROVED EVENTS
    // =====================================

    List<Event>
    findByStatusOrderByCreatedAtDesc(
            String status
    );

    // =====================================
    // PENDING EVENTS
    // =====================================

    List<Event>
    findByStatus(
            String status
    );

    // =====================================
    // CATEGORY FILTER
    // =====================================

    List<Event>
    findByCategory(
            String category
    );

    // =====================================
    // CREATOR EVENTS
    // =====================================

    List<Event>
    findByCreatedBy(
            String createdBy
    );

    // =====================================
    // LOCK EVENT DURING REGISTRATION
    // =====================================

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT e
            FROM Event e
            WHERE e.id = :eventId
            """)
    Optional<Event> findByIdForUpdate(
            @Param("eventId") Long eventId
    );
}

