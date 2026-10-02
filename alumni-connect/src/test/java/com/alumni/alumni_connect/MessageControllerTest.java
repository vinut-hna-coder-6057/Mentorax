package com.alumni.alumni_connect;

import com.alumni.alumni_connect.controller.MessageController;
import com.alumni.alumni_connect.dto.MessageRequest;
import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.service.MessageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageControllerTest {

    private MessageService messageService;
    private MessageController controller;

    @BeforeEach
    void setUp() {
        messageService = Mockito.mock(MessageService.class);
        controller = new MessageController(messageService);

        SecurityContextHolder.clearContext();
    }

    // =========================================================
    // STOMP SEND MESSAGE
    // =========================================================

    @Test
    void authenticatedPrincipalShouldSendMessageUsingPrincipalEmail() {

        MessageRequest request = new MessageRequest("bob@example.com", "Hello Bob");

        Principal principal = () -> "alice@example.com";

        controller.sendMessage(request, principal);

        verify(messageService).sendMessage(
                same(request),
                eq("alice@example.com")
        );
    }

    @Test
    void unauthenticatedStompMessageShouldBeRejected() {

        MessageRequest request = new MessageRequest("bob@example.com", "Hello Bob");

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> controller.sendMessage(request, null)
        );

        assertEquals(
                401,
                exception.getStatusCode().value()
        );

        assertEquals(
                "Authentication required",
                exception.getReason()
        );

        verify(
                messageService,
                never()
        ).sendMessage(any(MessageRequest.class), anyString());
    }

    // =========================================================
    // GET CONVERSATION
    // =========================================================

    @Test
    void authenticatedUserShouldRetrieveConversation() {

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        "alice@example.com",
                        null
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);

        Message message = new Message(
                "alice@example.com",
                "bob@example.com",
                "Hello Bob",
                null
        );

       when(
        messageService.getConversation(
                "alice@example.com",
                "bob@example.com",
                "alice@example.com"
        )
).thenReturn(List.of(message));
        List<com.alumni.alumni_connect.dto.MessageResponse> result =
                controller.getConversation(
                        "alice@example.com",
                        "bob@example.com"
                );

        assertEquals(1, result.size());

        assertEquals(
                "Hello Bob",
                result.get(0).content()
        );

        verify(messageService).getConversation(
        "alice@example.com",
        "bob@example.com",
        "alice@example.com"
);
    }

    @Test
    void userOutsideConversationShouldReceiveForbidden() {

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        "charlie@example.com",
                        null
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);

       when(
        messageService.getConversation(
                "alice@example.com",
                "bob@example.com",
                "charlie@example.com"
        )
).thenThrow(
                new IllegalArgumentException(
                        "You are not part of this conversation"
                )
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.getConversation(
                                "alice@example.com",
                                "bob@example.com"
                        )
                );

        assertEquals(
                403,
                exception.getStatusCode().value()
        );

        assertEquals(
                "You are not part of this conversation",
                exception.getReason()
        );
    }

    // =========================================================
    // GET INBOX CONVERSATIONS
    // =========================================================

    @Test
    void authenticatedUserShouldRetrieveInboxConversations() {

        Authentication authentication =
                new UsernamePasswordAuthenticationToken(
                        "alice@example.com",
                        null
                );

        SecurityContextHolder
                .getContext()
                .setAuthentication(authentication);

        controller.getConversations();

        verify(messageService).getConversations();
    }
}
