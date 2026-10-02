package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.dto.ConversationDTO;
import com.alumni.alumni_connect.dto.MessageResponse;
import com.alumni.alumni_connect.dto.MessageRequest;
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
import org.springframework.validation.annotation.Validated;
import jakarta.validation.Valid;

@RestController
@Validated
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
            @Valid @Payload MessageRequest request,
            Principal principal
    ) {
        if (principal == null) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required"
            );
        }

        messageService.sendMessage(
                request,
                principal.getName()
        );
    }

    // =====================================
    // GET CONVERSATION
    // =====================================

    @GetMapping("/messages/conversation")
    public List<MessageResponse> getConversation(
            @RequestParam String sender,
            @RequestParam String receiver,
            @RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="50") int size
    ) {

        String authenticatedEmail = SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();

        try {
            return messageService.getConversation(
                    sender,
                    receiver,
                    authenticatedEmail,page,size
            ).stream().map(MessageResponse::from).toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    e.getMessage()
            );
        }
    }

    public List<MessageResponse> getConversation(String sender, String receiver) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        try { return messageService.getConversation(sender, receiver, email).stream().map(MessageResponse::from).toList(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,e.getMessage()); }
    }

    // =====================================
    // GET INBOX CONVERSATIONS
    // =====================================

    @GetMapping("/conversations")
    public List<ConversationDTO> getConversations() {
        return messageService.getConversations();
    }
}
