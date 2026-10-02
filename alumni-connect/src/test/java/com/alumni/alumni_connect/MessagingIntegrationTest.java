package com.alumni.alumni_connect;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import org.springframework.security.core.context.SecurityContextHolder;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;


@SpringBootTest
@Transactional
class MessagingIntegrationTest {

    // =========================================================
    // DEPENDENCIES
    // =========================================================

    @Autowired
    private MessageService messageService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationParticipantRepository participantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private StompJwtChannelInterceptor interceptor;

    @MockBean
    private SimpMessagingTemplate messagingTemplate;

    @MockBean
    private NotificationService notificationService;

    private final MessageChannel messageChannel =
            mock(MessageChannel.class);


    // =========================================================
    // TEST USERS
    // =========================================================

    private User alice;
    private User bob;
    private User charlie;


    // =========================================================
    // SETUP
    // =========================================================

    @BeforeEach
    void setUpUsers() {

        String suffix =
                UUID.randomUUID().toString();

        alice =
                createUser(
                        "message-alice-" +
                                suffix +
                                "@example.test"
                );

        bob =
                createUser(
                        "message-bob-" +
                                suffix +
                                "@example.test"
                );

        charlie =
                createUser(
                        "message-charlie-" +
                                suffix +
                                "@example.test"
                );
    }


    // =========================================================
    // CLEAN SECURITY CONTEXT
    // =========================================================

    @AfterEach
    void clearSecurityContext() {

        SecurityContextHolder.clearContext();
    }


    // =========================================================
    // STOMP CONNECT
    // =========================================================

    @Test
    void validStompConnectEstablishesAuthenticatedPrincipal() {

        String token =
                jwtUtil.generateToken(
                        alice.getEmail(),
                        "STUDENT"
                );

        StompHeaderAccessor accessor =
                connectAccessor(
                        "Bearer " + token
                );

        org.springframework.messaging.Message<?> authenticated =
                interceptor.preSend(
                        MessageBuilder.createMessage(
                                new byte[0],
                                accessor.getMessageHeaders()
                        ),
                        messageChannel
                );

        assertNotNull(authenticated);

        assertNotNull(
                StompHeaderAccessor
                        .wrap(authenticated)
                        .getUser()
        );

        assertEquals(
                alice.getEmail(),
                StompHeaderAccessor
                        .wrap(authenticated)
                        .getUser()
                        .getName()
        );
    }


    // =========================================================
    // INVALID STOMP JWT
    // =========================================================

    @Test
    void missingOrInvalidStompJwtIsRejected() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        interceptor.preSend(
                                MessageBuilder.createMessage(
                                        new byte[0],
                                        connectAccessor(null)
                                                .getMessageHeaders()
                                ),
                                messageChannel
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        interceptor.preSend(
                                MessageBuilder.createMessage(
                                        new byte[0],
                                        connectAccessor("Bearer invalid")
                                                .getMessageHeaders()
                                ),
                                messageChannel
                        )
        );
    }


    // =========================================================
    // UNAUTHENTICATED STOMP SEND
    // =========================================================

    @Test
    void unauthenticatedStompSendIsRejected() {

        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(
                        StompCommand.SEND
                );

        accessor.setDestination(
                "/app/chat"
        );

        accessor.setLeaveMutable(true);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        interceptor.preSend(
                                MessageBuilder.createMessage(
                                        new byte[0],
                                        accessor.getMessageHeaders()
                                ),
                                messageChannel
                        )
        );
    }


    // =========================================================
    // PRIVATE SUBSCRIPTIONS
    // =========================================================

    @Test
    void privateSubscriptionsAreLimitedToAuthenticatedUserDestination() {

        String token =
                jwtUtil.generateToken(
                        alice.getEmail(),
                        "STUDENT"
                );

        StompHeaderAccessor connected =
                connectAccessor(
                        "Bearer " + token
                );

        org.springframework.messaging.Message<?> authenticated =
                interceptor.preSend(
                        MessageBuilder.createMessage(
                                new byte[0],
                                connected.getMessageHeaders()
                        ),
                        messageChannel
                );

        // -----------------------------------------------------
        // Own private destination
        // -----------------------------------------------------

        StompHeaderAccessor own =
                StompHeaderAccessor.create(
                        StompCommand.SUBSCRIBE
                );

        own.setDestination(
                "/user/queue/messages"
        );

        own.setUser(
                StompHeaderAccessor
                        .wrap(authenticated)
                        .getUser()
        );

        own.setLeaveMutable(true);

        assertDoesNotThrow(
                () ->
                        interceptor.preSend(
                                MessageBuilder.createMessage(
                                        new byte[0],
                                        own.getMessageHeaders()
                                ),
                                messageChannel
                        )
        );


        // -----------------------------------------------------
        // Another user's destination
        // -----------------------------------------------------

        StompHeaderAccessor other =
                StompHeaderAccessor.create(
                        StompCommand.SUBSCRIBE
                );

        other.setDestination(
                "/user/" +
                        bob.getEmail() +
                        "/queue/messages"
        );

        other.setUser(
                StompHeaderAccessor
                        .wrap(authenticated)
                        .getUser()
        );

        other.setLeaveMutable(true);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        interceptor.preSend(
                                MessageBuilder.createMessage(
                                        new byte[0],
                                        other.getMessageHeaders()
                                ),
                                messageChannel
                        )
        );
    }


    // =========================================================
    // MESSAGE PERSISTENCE + SENDER SECURITY + DELIVERY
    // =========================================================

    @Test
    void authenticatedSendPersistsAuthoritativeSenderConversationAndPrivateDelivery() {

        Message message =
                request(
                        bob.getEmail(),
                        "hello",
                        null
                );

        // Attempt sender spoofing
        message.setSenderEmail(
                charlie.getEmail()
        );

        messageService.sendMessage(
                message,
                alice.getEmail()
        );

        Message saved =
                messageRepository.findAll()
                        .stream()
                        .filter(
                                m ->
                                        "hello".equals(
                                                m.getContent()
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        // Server must ignore spoofed sender
        assertEquals(
                alice.getEmail(),
                saved.getSenderEmail()
        );

        assertEquals(
                alice.getId(),
                saved.getSender().getId()
        );

        assertNotNull(
                saved.getConversation()
        );

        assertNotNull(
                saved.getConversation()
                        .getLastMessageAt()
        );

        assertEquals(
                "hello",
                saved.getContent()
        );

        // Sender receives message
        verify(messagingTemplate)
                .convertAndSendToUser(
                        eq(alice.getEmail()),
                        eq("/queue/messages"),
                        eq(com.alumni.alumni_connect.dto.MessageResponse.from(saved))
                );

        // Receiver receives message
        verify(messagingTemplate)
                .convertAndSendToUser(
                        eq(bob.getEmail()),
                        eq("/queue/messages"),
                        eq(com.alumni.alumni_connect.dto.MessageResponse.from(saved))
                );
    }


    // =========================================================
    // PARTICIPANT + CONVERSATION SECURITY
    // =========================================================

    @Test
    void nonParticipantAndUnknownConversationCannotPersistOrUpdateActivity() {

        messageService.sendMessage(
                request(
                        bob.getEmail(),
                        "first",
                        null
                ),
                alice.getEmail()
        );

        Message first =
                messageRepository.findAll()
                        .stream()
                        .filter(
                                m ->
                                        "first".equals(
                                                m.getContent()
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        Long conversationId =
                first.getConversationId();

        LocalDateTime activity =
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow()
                        .getLastMessageAt();

        long count =
                messageRepository.count();


        // Charlie is not a participant
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        messageService.sendMessage(
                                request(
                                        bob.getEmail(),
                                        "forbidden",
                                        conversationId
                                ),
                                charlie.getEmail()
                        )
        );


        // Conversation does not exist
        assertThrows(
                org.springframework.web.server
                        .ResponseStatusException.class,
                () ->
                        messageService.sendMessage(
                                request(
                                        bob.getEmail(),
                                        "missing",
                                        Long.MAX_VALUE
                                ),
                                alice.getEmail()
                        )
        );


        // No message should have been persisted
        assertEquals(
                count,
                messageRepository.count()
        );

        // Conversation activity must remain unchanged
        assertEquals(
                activity,
                conversationRepository
                        .findById(conversationId)
                        .orElseThrow()
                        .getLastMessageAt()
        );

        assertTrue(
                participantRepository
                        .existsByConversation_IdAndUser_Id(
                                conversationId,
                                alice.getId()
                        )
        );

        assertTrue(
                participantRepository
                        .existsByConversation_IdAndUser_Id(
                                conversationId,
                                bob.getId()
                        )
        );

        assertFalse(
                participantRepository
                        .existsByConversation_IdAndUser_Id(
                                conversationId,
                                charlie.getId()
                        )
        );
    }


    // =========================================================
    // CANONICAL DIRECT CONVERSATION
    // =========================================================

    @Test
    void oppositeDirectionFirstMessagesReuseOneCanonicalConversation() {

        messageService.sendMessage(
                request(
                        bob.getEmail(),
                        "from-alice",
                        null
                ),
                alice.getEmail()
        );

        messageService.sendMessage(
                request(
                        alice.getEmail(),
                        "from-bob",
                        null
                ),
                bob.getEmail()
        );

        Message aliceMessage =
                messageRepository.findAll()
                        .stream()
                        .filter(
                                m ->
                                        "from-alice".equals(
                                                m.getContent()
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        Message bobMessage =
                messageRepository.findAll()
                        .stream()
                        .filter(
                                m ->
                                        "from-bob".equals(
                                                m.getContent()
                                        )
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                aliceMessage.getConversationId(),
                bobMessage.getConversationId()
        );

        assertTrue(
                conversationRepository
                        .findDirectConversation(
                                Math.min(
                                        alice.getId(),
                                        bob.getId()
                                ),
                                Math.max(
                                        alice.getId(),
                                        bob.getId()
                                )
                        )
                        .isPresent()
        );

        assertTrue(
                participantRepository
                        .existsByConversation_IdAndUser_Id(
                                aliceMessage.getConversationId(),
                                alice.getId()
                        )
        );

        assertTrue(
                participantRepository
                        .existsByConversation_IdAndUser_Id(
                                aliceMessage.getConversationId(),
                                bob.getId()
                        )
        );
    }


    // =========================================================
    // CONVERSATION RETRIEVAL
    // =========================================================

    @Test
    void normalizedConversationRetrievalRequiresParticipantAndKeepsLegacyCompatibilityIsolated() {

        Message legacy =
                new Message(
                        alice.getEmail(),
                        bob.getEmail(),
                        "legacy",
                        LocalDateTime.now()
                                .minusMinutes(1)
                );

        messageRepository.save(
                legacy
        );

        messageService.sendMessage(
                request(
                        bob.getEmail(),
                        "normalized",
                        null
                ),
                alice.getEmail()
        );

       assertEquals(
        2,
        messageService.getConversation(
                alice.getEmail(),
                bob.getEmail(),
                bob.getEmail()
        ).size()
);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        messageService.getConversation(
                                charlie.getEmail(),
                                alice.getEmail(),
                                bob.getEmail()
                        )
        );
    }

    @Test
void emptyMessageContentIsRejected() {

    Message message = request(
            bob.getEmail(),
            "   ",
            null
    );

    assertThrows(
            IllegalArgumentException.class,
            () -> messageService.sendMessage(
                    message,
                    alice.getEmail()
            )
    );
}
@Test
void messageContentLongerThan2000CharactersIsRejected() {

    String content = "a".repeat(2001);

    Message message = request(
            bob.getEmail(),
            content,
            null
    );

    assertThrows(
            IllegalArgumentException.class,
            () -> messageService.sendMessage(
                    message,
                    alice.getEmail()
            )
    );
}
    // =========================================================
    // CONCURRENT FIRST MESSAGES
    // =========================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentFirstMessagesShareTheCanonicalConversation()
            throws Exception {

        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        try {

            Future<?> fromAlice =
                    executor.submit(
                            () ->
                                    messageService.sendMessage(
                                            request(
                                                    bob.getEmail(),
                                                    "concurrent-alice",
                                                    null
                                            ),
                                            alice.getEmail()
                                    )
                    );

            Future<?> fromBob =
                    executor.submit(
                            () ->
                                    messageService.sendMessage(
                                            request(
                                                    alice.getEmail(),
                                                    "concurrent-bob",
                                                    null
                                            ),
                                            bob.getEmail()
                                    )
                    );

            fromAlice.get();
            fromBob.get();

        } finally {

            executor.shutdownNow();
        }

        Message aliceMessage =
                messageRepository.findAll()
                        .stream()
                        .filter(
                                m ->
                                        "concurrent-alice"
                                                .equals(
                                                        m.getContent()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();

        Message bobMessage =
                messageRepository.findAll()
                        .stream()
                        .filter(
                                m ->
                                        "concurrent-bob"
                                                .equals(
                                                        m.getContent()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();

        assertEquals(
                aliceMessage.getConversationId(),
                bobMessage.getConversationId()
        );

        assertTrue(
                conversationRepository
                        .findDirectConversation(
                                Math.min(
                                        alice.getId(),
                                        bob.getId()
                                ),
                                Math.max(
                                        alice.getId(),
                                        bob.getId()
                                )
                        )
                        .isPresent()
        );
    }


    // =========================================================
    // MESSAGE NOTIFICATION
    // =========================================================

    @Test
    void sendingMessageShouldCreateNotificationForReceiver() {

        Message message =
                request(
                        bob.getEmail(),
                        "Hello Bob",
                        null
                );

        messageService.sendMessage(
                message,
                alice.getEmail()
        );

        verify(notificationService)
                .sendNotification(
                        eq(bob.getEmail()),
                        eq(
                                "New message from " +
                                        alice.getEmail()
                        ),
                        eq("MESSAGE"),
                        eq(
                                "/chat/" +
                                        alice.getEmail()
                        )
                );
    }


    // =========================================================
    // INBOX — LATEST MESSAGE PER CONVERSATION
    // =========================================================
    
    @Test
    void inboxShouldReturnOnlyLatestMessageForEachConversation() {

        // -----------------------------------------------------
        // Alice → Bob: first message
        // -----------------------------------------------------

        messageService.sendMessage(
                request(
                        bob.getEmail(),
                        "First message",
                        null
                ),
                alice.getEmail()
        );


        // -----------------------------------------------------
        // Alice → Bob: latest message
        // -----------------------------------------------------

        messageService.sendMessage(
                request(
                        bob.getEmail(),
                        "Latest message",
                        null
                ),
                alice.getEmail()
        );


        // -----------------------------------------------------
        // Alice → Charlie
        // -----------------------------------------------------

        messageService.sendMessage(
                request(
                        charlie.getEmail(),
                        "Charlie message",
                        null
                ),
                alice.getEmail()
        );


        // getConversations() gets the current
        // user from SecurityContextHolder.

        var authentication =
                new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(
                                alice.getEmail(),
                                null
                        );

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        authentication
                );


        List<ConversationDTO> conversations =
                messageService.getConversations();


        // Alice has exactly two conversations:
        //
        // Alice ↔ Bob
        // Alice ↔ Charlie

        assertEquals(
                2,
                conversations.size()
        );


        // -----------------------------------------------------
        // Find Bob conversation
        // -----------------------------------------------------

        ConversationDTO bobConversation =
                conversations.stream()
                        .filter(
                                c ->
                                        c.getEmail()
                                                .equals(
                                                        bob.getEmail()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();


        // -----------------------------------------------------
        // Find Charlie conversation
        // -----------------------------------------------------

        ConversationDTO charlieConversation =
                conversations.stream()
                        .filter(
                                c ->
                                        c.getEmail()
                                                .equals(
                                                        charlie.getEmail()
                                                )
                        )
                        .findFirst()
                        .orElseThrow();


        // -----------------------------------------------------
        // Bob conversation must show latest message
        // -----------------------------------------------------

        assertEquals(
                "Latest message",
                bobConversation.getLatestMessage()
        );


        // -----------------------------------------------------
        // Charlie conversation
        // -----------------------------------------------------

        assertEquals(
                "Charlie message",
                charlieConversation.getLatestMessage()
        );


        // -----------------------------------------------------
        // Timestamp should exist
        // -----------------------------------------------------

        assertNotNull(
                bobConversation.getTimestamp()
        );

        assertNotNull(
                charlieConversation.getTimestamp()
        );
    }

    @Test
    void inboxConversationPagesAreBoundedAndDoNotRepeatThreads() {
        User dave = createUser("message-dave-" + UUID.randomUUID() + "@example.test");
        messageService.sendMessage(request(bob.getEmail(), "Bob thread", null), alice.getEmail());
        messageService.sendMessage(request(charlie.getEmail(), "Charlie thread", null), alice.getEmail());
        messageService.sendMessage(request(dave.getEmail(), "Dave thread", null), alice.getEmail());
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        alice.getEmail(), null));

        List<ConversationDTO> firstPage = messageService.getConversations(0, 1);
        List<ConversationDTO> secondPage = messageService.getConversations(1, 1);
        List<ConversationDTO> boundedNegativePage = messageService.getConversations(-1, 0);

        assertEquals(1, firstPage.size());
        assertEquals(1, secondPage.size());
        assertNotEquals(firstPage.get(0).getEmail(), secondPage.get(0).getEmail());
        assertEquals(firstPage.get(0).getEmail(), boundedNegativePage.get(0).getEmail());
    }

    // =========================================================
    // CREATE TEST USER
    // =========================================================

    private User createUser(
            String email
    ) {

        User user =
                new User(
                        "Messaging Test",
                        email,
                        "not-used",
                        "STUDENT",
                        "APPROVED"
                );

        return userRepository.save(
                user
        );
    }


    // =========================================================
    // CREATE MESSAGE REQUEST
    // =========================================================

    private Message request(
            String receiver,
            String content,
            Long conversationId
    ) {

        Message message =
                new Message();

        message.setReceiverEmail(
                receiver
        );

        message.setContent(
                content
        );

        message.setConversationId(
                conversationId
        );

        return message;
    }


    // =========================================================
    // CREATE STOMP CONNECT ACCESSOR
    // =========================================================

    private StompHeaderAccessor connectAccessor(
            String authorization
    ) {

        StompHeaderAccessor accessor =
                StompHeaderAccessor.create(
                        StompCommand.CONNECT
                );

        if (authorization != null) {

            accessor.setNativeHeader(
                    "Authorization",
                    authorization
            );
        }

        accessor.setLeaveMutable(
                true
        );

        return accessor;
    }
}
