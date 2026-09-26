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
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    Optional<Conversation> findByDirectUserLowIdAndDirectUserHighId(
            Long directUserLowId, Long directUserHighId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO conversations
                (direct_user_low_id, direct_user_high_id, created_at, updated_at)
            VALUES (:firstUserId, :secondUserId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    void createDirectConversationIfAbsent(
            @Param("firstUserId") Long firstUserId,
            @Param("secondUserId") Long secondUserId);

    @Query("""
            select c from Conversation c
            where c.directUserLowId = :firstUserId
              and c.directUserHighId = :secondUserId
            """)
    Optional<Conversation> findDirectConversation(
            @Param("firstUserId") Long firstUserId,
            @Param("secondUserId") Long secondUserId);
}

