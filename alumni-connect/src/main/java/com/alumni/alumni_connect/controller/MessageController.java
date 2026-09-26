package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.ConversationDTO;
import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.service.MessageService;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    // =====================================
    // SEND MESSAGE
    // =====================================

    @MessageMapping("/chat")
    public void sendMessage(
            @Payload Message message,
            Principal principal
    ) {
        if (principal == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required"
            );
        }

        messageService.sendMessage(
                message,
                principal.getName()
        );
    }

    // =====================================
    // GET CONVERSATION
    // =====================================

    @GetMapping("/messages/conversation")
    public List<Message> getConversation(
            @RequestParam String sender,
            @RequestParam String receiver
    ) {

        String authenticatedEmail = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        try {
            return messageService.getConversation(
                    sender,
                    receiver,
                    authenticatedEmail
            );
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    e.getMessage()
            );
        }
    }

    // =====================================
    // GET INBOX CONVERSATIONS
    // =====================================

    @GetMapping("/conversations")
    public List<ConversationDTO> getConversations() {
        return messageService.getConversations();
    }
}