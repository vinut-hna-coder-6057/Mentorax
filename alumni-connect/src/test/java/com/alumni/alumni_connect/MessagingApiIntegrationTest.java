package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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

    private User alice;
    private User bob;
    private User charlie;

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
                        .header(
                                "Authorization",
                                bearer(alice.getEmail(), "STUDENT")
                        )
        )
        .andExpect(status().isOk());
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