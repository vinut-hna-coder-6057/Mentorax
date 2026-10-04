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
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = {"requester", "receiver"})
    @Query("select c from Connection c where c.id = :id")
    Optional<Connection> findByIdForUpdateWithUsers(@Param("id") Long id);

    @EntityGraph(attributePaths = {"requester", "receiver"})
    Optional<Connection> findByRequester_IdAndReceiver_Id(Long requesterId, Long receiverId);
    List<Connection> findByRequester_IdOrReceiver_Id(Long requesterId, Long receiverId);
    List<Connection> findByRequester_IdOrReceiver_Id(Long requesterId, Long receiverId, Pageable pageable);
}
