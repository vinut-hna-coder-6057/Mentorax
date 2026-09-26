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

import java.util.List;
import java.util.Optional;

public interface EventRegistrationRepository

        extends JpaRepository<
        EventRegistration,
        Long
        > {

    // =====================================
    // FIND BY EVENT
    // =====================================

    List<EventRegistration>

    findByEventId(Long eventId);

    // =====================================
    // CHECK EXISTING REGISTRATION
    // =====================================

    boolean existsByEventIdAndStudentEmail(

            Long eventId,

            String studentEmail
    );

    boolean existsByEventIdAndUser_Id(Long eventId, Long userId);

    Optional<EventRegistration> findByEventIdAndUser_Id(Long eventId, Long userId);

    Optional<EventRegistration> findByEventIdAndStudentEmail(

            Long eventId,

            String studentEmail
    );

    // =====================================
    // COUNT REGISTRATIONS
    // =====================================

    int countByEventId(

            Long eventId
    );

    // =====================================
    // DELETE REGISTRATION
    // =====================================

    void deleteByEventIdAndStudentEmail(

            Long eventId,

            String studentEmail
    );

    // =====================================
    // STUDENT EVENTS
    // =====================================

    List<EventRegistration>

    findByStudentEmail(

            String studentEmail
    );

    List<EventRegistration> findByUser_Id(Long userId);
}

