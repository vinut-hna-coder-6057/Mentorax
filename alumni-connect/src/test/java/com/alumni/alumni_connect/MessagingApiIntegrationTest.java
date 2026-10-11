package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.entity.ConversationParticipantId;
import com.alumni.alumni_connect.repository.ConversationParticipantRepository;
import com.alumni.alumni_connect.repository.ConversationRepository;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import com.alumni.alumni_connect.service.MessageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.hasSize;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MessagingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationParticipantRepository participantRepository;

    @Autowired
    private MessageService messageService;

    private User alice;
    private User bob;
    private User charlie;

    @Test
    void sockJsTransportHandshakeIsPublicButDoesNotReplaceStompAuthentication()
            throws Exception {
        mockMvc.perform(get("/chat/info")
                        .header("Origin", "http://localhost"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(
                        MediaType.APPLICATION_JSON));
    }

    @BeforeEach
    void setUpUsers() {

        String suffix = UUID.randomUUID().toString();

        alice = createUser(
                "api-alice-" + suffix + "@example.test"
        );

        bob = createUser(
                "api-bob-" + suffix + "@example.test"
        );

        charlie = createUser(
                "api-charlie-" + suffix + "@example.test"
        );
    }

    // =========================================================
    // GET CONVERSATION
    // =========================================================

    @Test
    void authenticatedConversationParticipantShouldGetConversation()
            throws Exception {

        Message message = new Message(
                alice.getEmail(),
                bob.getEmail(),
                "Hello Bob",
                LocalDateTime.now()
        );

        messageRepository.save(message);

        mockMvc.perform(
                get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .header(
                                "Authorization",
                                bearer(alice.getEmail(), "STUDENT")
                        )
        )
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$[0].content")
                .value("Hello Bob"))
        .andExpect(jsonPath("$[0].senderEmail")
                .value(alice.getEmail()))
        .andExpect(jsonPath("$[0].receiverEmail")
                .value(bob.getEmail()));
    }

    @Test
    void authenticatedReceiverCanGetLegacyConversationMessages() throws Exception {
        messageRepository.save(new Message(
                alice.getEmail(), bob.getEmail(), "Hello Alice", LocalDateTime.now()));

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .header("Authorization", bearer(bob.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Hello Alice"));
    }

    @Test
    void bothNormalizedConversationParticipantsCanReadMessages() throws Exception {
        messageService.sendMessage(
                new Message(alice.getEmail(), bob.getEmail(), "Normalized message", LocalDateTime.now()),
                alice.getEmail());

        for (User participant : new User[] {alice, bob}) {
            mockMvc.perform(get("/messages/conversation")
                            .param("sender", alice.getEmail())
                            .param("receiver", bob.getEmail())
                            .header("Authorization", bearer(participant.getEmail(), "STUDENT")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].content").value("Normalized message"));
        }
    }

    @Test
    void conversationLookupAcceptsEmailCaseDifferencesWithoutChangingIdentity()
            throws Exception {
        messageRepository.save(new Message(
                alice.getEmail(), bob.getEmail(), "Case-insensitive lookup", LocalDateTime.now()));

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail().toUpperCase(Locale.ROOT))
                        .param("receiver", bob.getEmail().toUpperCase(Locale.ROOT))
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Case-insensitive lookup"));
    }

    @Test
    void unknownConversationUsersReturnNotFound() throws Exception {
        String unknownEmail = "missing-" + UUID.randomUUID() + "@example.test";

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", unknownEmail)
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", unknownEmail)
                        .param("receiver", bob.getEmail())
                        .header("Authorization", bearer(bob.getEmail(), "STUDENT")))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingConversationUsersReturnBadRequest() throws Exception {
        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/messages/conversation")
                        .param("receiver", bob.getEmail())
                        .header("Authorization", bearer(bob.getEmail(), "STUDENT")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conversationPagesReturnNewestWindowChronologicallyWithStableTimestampTieBreaks()
            throws Exception {
        LocalDateTime sameTimestamp = LocalDateTime.of(2026, 1, 1, 12, 0);
        for (int i = 0; i < 130; i++) {
            messageRepository.save(new Message(
                    alice.getEmail(), bob.getEmail(), "message-" + i, sameTimestamp));
        }

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .param("page", "0")
                        .param("size", "50")
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(50)))
                .andExpect(jsonPath("$[0].content").value("message-80"))
                .andExpect(jsonPath("$[49].content").value("message-129"));

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .param("page", "1")
                        .param("size", "50")
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(50)))
                .andExpect(jsonPath("$[0].content").value("message-30"))
                .andExpect(jsonPath("$[49].content").value("message-79"));
    }

    @Test
    void conversationPaginationCapsPageSizeAndClampsNegativePage()
            throws Exception {
        for (int i = 0; i < 130; i++) {
            messageRepository.save(new Message(
                    alice.getEmail(), bob.getEmail(), "bounded-" + i,
                    LocalDateTime.of(2026, 2, 1, 12, 0).plusSeconds(i)));
        }

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .param("page", "-5")
                        .param("size", "1000")
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(100)))
                .andExpect(jsonPath("$[0].content").value("bounded-30"))
                .andExpect(jsonPath("$[99].content").value("bounded-129"));
    }

    @Test
    void conversationPaginationReturnsEmptyWhenPageIsBeyondHistory()
            throws Exception {
        messageRepository.save(new Message(
                alice.getEmail(), bob.getEmail(), "only-message", LocalDateTime.now()));

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .param("page", "1")
                        .param("size", "1")
                        .header("Authorization", bearer(alice.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // =========================================================
    // USER OUTSIDE CONVERSATION
    // =========================================================

    @Test
    void userOutsideConversationShouldReceive403()
            throws Exception {

        mockMvc.perform(
                get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .header(
                                "Authorization",
                                bearer(charlie.getEmail(), "STUDENT")
                        )
        )
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value(
                        "You are not part of this conversation"
                ));
    }

    @Test
    void normalizedConversationRequiresCurrentParticipantMembership() throws Exception {
        messageService.sendMessage(
                new Message(alice.getEmail(), bob.getEmail(), "Normalized message", LocalDateTime.now()),
                alice.getEmail());
        long lowId = Math.min(alice.getId(), bob.getId());
        long highId = Math.max(alice.getId(), bob.getId());
        var conversation = conversationRepository.findDirectConversation(lowId, highId)
                .orElseThrow();
        participantRepository.deleteById(
                new ConversationParticipantId(conversation.getId(), bob.getId()));

        mockMvc.perform(get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
                        .header("Authorization", bearer(bob.getEmail(), "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error")
                        .value("You are not part of this conversation"));
    }

    // =========================================================
    // UNAUTHENTICATED REQUEST
    // =========================================================

    @Test
    void unauthenticatedConversationRequestShouldReturn401()
            throws Exception {

        mockMvc.perform(
                get("/messages/conversation")
                        .param("sender", alice.getEmail())
                        .param("receiver", bob.getEmail())
        )
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Authentication required"));
    }

    // =========================================================
    // GET INBOX CONVERSATIONS
    // =========================================================

    @Test
    void authenticatedUserShouldGetConversations()
            throws Exception {

        Message message = new Message(
                alice.getEmail(),
                bob.getEmail(),
                "Hello Bob",
                LocalDateTime.now()
        );

        messageRepository.save(message);
        mockMvc.perform(
        get("/conversations")
                .param("page", "0")
                .param("size", "1")
                .header(
                        "Authorization",
                        bearer(alice.getEmail(), "STUDENT")
                )
)
.andExpect(status().isOk())
.andExpect(content().contentTypeCompatibleWith(
        MediaType.APPLICATION_JSON
))
.andExpect(jsonPath("$[0].email")
        .value(bob.getEmail()))
.andExpect(jsonPath("$[0].latestMessage")
        .value("Hello Bob"))
.andExpect(jsonPath("$[0].timestamp")
        .exists());
       
    }

    // =========================================================
    // UNAUTHENTICATED INBOX
    // =========================================================

    @Test
    void unauthenticatedInboxRequestShouldReturn401()
            throws Exception {

        mockMvc.perform(
                get("/conversations")
        )
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Authentication required"));
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private User createUser(String email) {

        User user = new User(
                "Messaging API Test",
                email,
                "not-used",
                "STUDENT",
                "APPROVED"
        );
        user.setEmailVerified(true);

        return userRepository.save(user);
    }

    private String bearer(
            String email,
            String role
    ) {

        return "Bearer " +
                jwtUtil.generateToken(email, role);
    }
}
