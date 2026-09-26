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

public interface ConversationParticipantRepository
        extends JpaRepository<ConversationParticipant, ConversationParticipantId> {
    boolean existsByConversation_IdAndUser_Id(Long conversationId, Long userId);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO conversation_participants (conversation_id, user_id, joined_at)
            VALUES (:conversationId, :userId, CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    void addParticipantIfAbsent(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId);
}

