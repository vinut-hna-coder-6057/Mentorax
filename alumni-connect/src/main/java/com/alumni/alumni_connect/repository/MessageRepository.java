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
import org.springframework.data.domain.Pageable;

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
    @Query("SELECT m FROM Message m WHERE m.conversation IS NULL AND ((m.senderEmail = :sender AND m.receiverEmail = :receiver) OR (m.senderEmail = :receiver AND m.receiverEmail = :sender)) ORDER BY m.id ASC")
    List<Message> findConversation(@Param("sender") String sender,@Param("receiver") String receiver,Pageable pageable);

    // =========================================
    List<Message> findByConversation_IdOrderByIdAsc(Long conversationId);
    List<Message> findByConversation_IdOrderByIdAsc(Long conversationId,Pageable pageable);

    @Query("""
            SELECT m FROM Message m
            WHERE (m.conversation IS NOT NULL AND m.conversation.id = :conversationId)
               OR (m.conversation IS NULL AND (
                    (m.senderEmail = :sender AND m.receiverEmail = :receiver)
                    OR (m.senderEmail = :receiver AND m.receiverEmail = :sender)))
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findConversationPage(
            @Param("conversationId") Long conversationId,
            @Param("sender") String sender,
            @Param("receiver") String receiver,
            Pageable pageable);

    @Query("""
            SELECT m FROM Message m
            WHERE ((m.conversation IS NOT NULL AND EXISTS (
                        SELECT p FROM ConversationParticipant p
                        WHERE p.conversation = m.conversation AND p.user.id = :userId))
                    OR (m.conversation IS NULL
                        AND (m.senderEmail = :email OR m.receiverEmail = :email)))
              AND NOT EXISTS (
                    SELECT newer FROM Message newer
                    WHERE ((newer.conversation IS NOT NULL AND EXISTS (
                                SELECT p2 FROM ConversationParticipant p2
                                WHERE p2.conversation = newer.conversation AND p2.user.id = :userId))
                            OR (newer.conversation IS NULL
                                AND (newer.senderEmail = :email OR newer.receiverEmail = :email)))
                      AND (CASE WHEN newer.senderEmail = :email THEN newer.receiverEmail ELSE newer.senderEmail END)
                            = (CASE WHEN m.senderEmail = :email THEN m.receiverEmail ELSE m.senderEmail END)
                      AND (newer.timestamp > m.timestamp
                            OR (newer.timestamp = m.timestamp AND newer.id > m.id)
                            OR (newer.timestamp IS NOT NULL AND m.timestamp IS NULL)
                            OR (newer.timestamp IS NULL AND m.timestamp IS NULL AND newer.id > m.id))
              )
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findLatestMessagesForParticipant(
            @Param("userId") Long userId,
            @Param("email") String email);

    @Query("""
            SELECT m FROM Message m
            JOIN ConversationParticipant p ON p.conversation = m.conversation
            WHERE p.user.id = :userId
            ORDER BY m.timestamp DESC, m.id DESC
            """)
    List<Message> findMessagesForParticipant(@Param("userId") Long userId);
    @Query("SELECT m FROM Message m JOIN ConversationParticipant p ON p.conversation = m.conversation WHERE p.user.id = :userId ORDER BY m.timestamp DESC, m.id DESC")
    List<Message> findMessagesForParticipant(@Param("userId") Long userId,Pageable pageable);

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
    @Query("SELECT m FROM Message m WHERE m.conversation IS NULL AND (m.senderEmail = :email OR m.receiverEmail = :email) ORDER BY m.id DESC")
    List<Message> findInboxMessages(@Param("email") String email,Pageable pageable);
}

