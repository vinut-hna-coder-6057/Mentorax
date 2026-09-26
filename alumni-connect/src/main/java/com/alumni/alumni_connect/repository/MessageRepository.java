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

import org.springframework.data.jpa.repository.Query;

import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MessageRepository
        extends JpaRepository<Message, Long> {

    // =========================================
    // Legacy-only compatibility reads. New messages are read by conversation ID.
    // =========================================

    @Query("""

        SELECT m FROM Message m

        WHERE

        m.conversation IS NULL
        AND (
            (m.senderEmail = :sender AND m.receiverEmail = :receiver)
            OR
            (m.senderEmail = :receiver AND m.receiverEmail = :sender)
        )

        ORDER BY m.id ASC

    """)

    List<Message> findConversation(

            @Param("sender")
            String sender,

            @Param("receiver")
            String receiver
    );

    // =========================================
    List<Message> findByConversation_IdOrderByIdAsc(Long conversationId);

    @Query("""
            SELECT m FROM Message m
            JOIN ConversationParticipant p ON p.conversation = m.conversation
            WHERE p.user.id = :userId
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findMessagesForParticipant(@Param("userId") Long userId);

    // Legacy-only compatibility inbox reads.
    // =========================================

    @Query("""

        SELECT m FROM Message m

        WHERE m.conversation IS NULL
        AND (m.senderEmail = :email OR m.receiverEmail = :email)

        ORDER BY m.id DESC

    """)

    List<Message> findAllMessages(

            @Param("email")
            String email
    );
    @Query("""

    SELECT m

    FROM Message m

    WHERE m.conversation IS NULL
    AND (m.senderEmail = :email OR m.receiverEmail = :email)

    ORDER BY m.id DESC

""")

    List<Message> findInboxMessages(

            @Param("email")
            String email
    );
}

