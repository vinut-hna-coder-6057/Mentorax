package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.ConversationDTO;
import com.alumni.alumni_connect.entity.Conversation;
import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ConversationParticipantRepository;
import com.alumni.alumni_connect.repository.ConversationRepository;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.UserRepository;

import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MessageService {

    private final SimpMessagingTemplate messagingTemplate;
    private final MessageRepository repository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final ConversationParticipantRepository participantRepository;

    public MessageService(
            SimpMessagingTemplate messagingTemplate,
            MessageRepository repository,
            NotificationService notificationService,
            UserRepository userRepository,
            ConversationRepository conversationRepository,
            ConversationParticipantRepository participantRepository
    ) {
        this.messagingTemplate = messagingTemplate;
        this.repository = repository;
        this.notificationService = notificationService;
        this.userRepository = userRepository;
        this.conversationRepository = conversationRepository;
        this.participantRepository = participantRepository;
    }

    // =====================================
    // SEND MESSAGE
    // =====================================

    @Transactional
    public void sendMessage(Message message, String authenticatedEmail) {

        if (authenticatedEmail == null || authenticatedEmail.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authentication required"
            );
        }

        // Receiver is required
        if (message.getReceiverEmail() == null
                || message.getReceiverEmail().isBlank()) {

            throw new IllegalArgumentException("Receiver is required");
        }

        // Get the real authenticated sender from the database.
        // Never trust sender information coming from the frontend.
        User sender = userRepository.findByEmail(authenticatedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Authenticated user no longer exists"
                ));

        // Get receiver
        User receiver = userRepository.findByEmail(message.getReceiverEmail())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Receiver not found"
                ));

        // Prevent self-messaging
        if (sender.getId().equals(receiver.getId())) {
            throw new IllegalArgumentException(
                    "A direct conversation requires two different users"
            );
        }

        // Server controls sender
        message.setSenderEmail(sender.getEmail());
        message.setSender(sender);

        Conversation conversation;

        if (message.getConversationId() != null) {

            conversation = conversationRepository
                    .findById(message.getConversationId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Conversation not found"
                    ));

            // The authenticated sender and receiver must both belong
            // to the requested conversation.
            boolean senderIsParticipant =
                    participantRepository.existsByConversation_IdAndUser_Id(
                            conversation.getId(),
                            sender.getId()
                    );

            boolean receiverIsParticipant =
                    participantRepository.existsByConversation_IdAndUser_Id(
                            conversation.getId(),
                            receiver.getId()
                    );

            if (!senderIsParticipant || !receiverIsParticipant) {
                throw new IllegalArgumentException(
                        "Conversation membership is invalid"
                );
            }

        } else {

            conversation = getOrCreateDirectConversation(
                    sender,
                    receiver
            );
        }

        message.setConversation(conversation);

        // Validate message content
        if (message.getContent() == null
                || message.getContent().isBlank()) {

            throw new IllegalArgumentException(
                    "Message content is required"
            );
        }

        if (message.getContent().length() > 2000) {

            throw new IllegalArgumentException(
                    "Message content is too long"
            );
        }

        // Server controls timestamp
        message.setTimestamp(LocalDateTime.now());

        // Save
        Message saved = repository.save(message);

        // Deliver to receiver
        messagingTemplate.convertAndSendToUser(
                saved.getReceiverEmail(),
                "/queue/messages",
                saved
        );

        // Deliver to sender as well
        messagingTemplate.convertAndSendToUser(
                saved.getSenderEmail(),
                "/queue/messages",
                saved
        );

        // Notification
        notificationService.sendNotification(
                saved.getReceiverEmail(),
                "New message from " + saved.getSenderEmail(),
                "MESSAGE",
                "/chat/" + saved.getSenderEmail()
        );

        // Update conversation timestamp
        conversation.setLastMessageAt(saved.getTimestamp());
        conversationRepository.save(conversation);
    }

    // =====================================
    // GET OR CREATE DIRECT CONVERSATION
    // =====================================

    private Conversation getOrCreateDirectConversation(
            User first,
            User second
    ) {

        if (first.getId().equals(second.getId())) {
            throw new IllegalArgumentException(
                    "A direct conversation requires two different users"
            );
        }

        long lowId = Math.min(
                first.getId(),
                second.getId()
        );

        long highId = Math.max(
                first.getId(),
                second.getId()
        );

        conversationRepository.createDirectConversationIfAbsent(
                lowId,
                highId
        );

        Conversation conversation =
                conversationRepository
                        .findDirectConversation(lowId, highId)
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "Direct conversation was not created"
                                )
                        );

        participantRepository.addParticipantIfAbsent(
                conversation.getId(),
                first.getId()
        );

        participantRepository.addParticipantIfAbsent(
                conversation.getId(),
                second.getId()
        );

        return conversation;
    }

    // =====================================
    // GET CONVERSATION
    // =====================================

    @Transactional(readOnly = true)
    public List<Message> getConversation(
            String sender,
            String receiver,
            String authenticatedEmail
    ) {

        if (authenticatedEmail == null
                || authenticatedEmail.isBlank()) {

            throw new IllegalArgumentException(
                    "Authentication required"
            );
        }

        if (sender == null
                || sender.isBlank()
                || receiver == null
                || receiver.isBlank()) {

            throw new IllegalArgumentException(
                    "Sender and receiver are required"
            );
        }

        // The authenticated user must be one of the two users.
        if (!authenticatedEmail.equalsIgnoreCase(sender)
                && !authenticatedEmail.equalsIgnoreCase(receiver)) {

           throw new IllegalArgumentException(
    "You are not part of this conversation"
);
        }

        User senderUser = userRepository.findByEmail(sender)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Sender not found"
                        )
                );

        User receiverUser = userRepository.findByEmail(receiver)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Receiver not found"
                        )
                );
      long lowId = Math.min(
        senderUser.getId(),
        receiverUser.getId()
);

long highId = Math.max(
        senderUser.getId(),
        receiverUser.getId()
);

Conversation conversation =
        conversationRepository
                .findDirectConversation(lowId, highId)
                .orElse(null);

// The authenticated user must actually belong to the requested
// conversation. This check also protects legacy conversations,
// where there may not be a normalized Conversation row yet.
if (conversation != null) {

    User authenticatedUser =
            userRepository.findByEmail(authenticatedEmail)
                    .orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Authenticated user not found"
                            ));

    boolean authenticatedUserIsParticipant =
            participantRepository.existsByConversation_IdAndUser_Id(
                    conversation.getId(),
                    authenticatedUser.getId()
            );

    if (!authenticatedUserIsParticipant) {
        throw new IllegalArgumentException(
                "You are not part of this conversation"
        );
    }
}

List<Message> messages = new ArrayList<>();

// New normalized conversation messages
if (conversation != null) {
    messages.addAll(
            repository.findByConversation_IdOrderByIdAsc(
                    conversation.getId()
            )
    );
}
        // Legacy messages
        messages.addAll(
                repository.findConversation(
                        sender,
                        receiver
                )
        );

        messages.addAll(
                repository.findConversation(
                        receiver,
                        sender
                )
        );

        /*
         * A message should appear only once.
         *
         * Normalized messages and legacy messages are stored
         * differently, so deduplicate by database ID.
         */
        Map<Long, Message> uniqueMessages =
                new LinkedHashMap<>();

        for (Message message : messages) {

            if (message.getId() != null) {
                uniqueMessages.put(
                        message.getId(),
                        message
                );
            }
        }

        List<Message> result =
                new ArrayList<>(uniqueMessages.values());

        // Oldest -> newest for chat screen
        result.sort(
                Comparator
                        .comparing(
                                Message::getTimestamp,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
                        .thenComparing(
                                Message::getId,
                                Comparator.nullsLast(
                                        Comparator.naturalOrder()
                                )
                        )
        );

        return result;
    }

    // =====================================
    // GET ALL CONVERSATIONS / INBOX
    // =====================================

    @Transactional(readOnly = true)
    public List<ConversationDTO> getConversations() {

        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()) {

            throw new IllegalArgumentException(
                    "Authentication required"
            );
        }

        String authenticatedEmail =
                authentication.getName();

        User currentUser =
                userRepository.findByEmail(authenticatedEmail)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "User not found"
                                )
                        );

        List<Message> messages =
                new ArrayList<>();

        // New normalized messages
        messages.addAll(
                repository.findMessagesForParticipant(
                        currentUser.getId()
                )
        );

        // Legacy messages
        messages.addAll(
                repository.findInboxMessages(
                        authenticatedEmail
                )
        );

        /*
         * One conversation can contain many messages.
         *
         * Keep only the newest message for each other user.
         */
        Map<String, Message> latestByParticipant =
                new LinkedHashMap<>();

        for (Message message : messages) {

            String otherEmail;

            if (authenticatedEmail.equalsIgnoreCase(
                    message.getSenderEmail()
            )) {

                otherEmail =
                        message.getReceiverEmail();

            } else {

                otherEmail =
                        message.getSenderEmail();
            }

            if (otherEmail == null
                    || otherEmail.isBlank()) {
                continue;
            }

            Message existing =
                    latestByParticipant.get(otherEmail);

            if (existing == null
                    || isNewer(message, existing)) {

                latestByParticipant.put(
                        otherEmail,
                        message
                );
            }
        }

        List<Message> latestMessages =
                new ArrayList<>(
                        latestByParticipant.values()
                );

        // Newest conversation first
        latestMessages.sort(
                Comparator
                        .comparing(
                                Message::getTimestamp,
                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        )
                        .thenComparing(
                                Message::getId,
                                Comparator.nullsLast(
                                        Comparator.reverseOrder()
                                )
                        )
        );

        List<ConversationDTO> conversations =
                new ArrayList<>();

        for (Message message : latestMessages) {

            String otherEmail;

            if (authenticatedEmail.equalsIgnoreCase(
                    message.getSenderEmail()
            )) {

                otherEmail =
                        message.getReceiverEmail();

            } else {

                otherEmail =
                        message.getSenderEmail();
            }

            if (otherEmail == null
                    || otherEmail.isBlank()) {
                continue;
            }

            User otherUser =
                    userRepository.findByEmail(otherEmail)
                            .orElse(null);

            if (otherUser == null) {
                continue;
            }

            ConversationDTO dto =
                    new ConversationDTO();

            dto.setEmail(
                    otherUser.getEmail()
            );

            dto.setLatestMessage(
                    message.getContent()
            );

            dto.setTimestamp(
                    message.getTimestamp()
            );

            conversations.add(dto);
        }

        return conversations;
    }

    // =====================================
    // MESSAGE COMPARISON
    // =====================================

    private boolean isNewer(
            Message first,
            Message second
    ) {

        if (first.getTimestamp() == null) {
            return false;
        }

        if (second.getTimestamp() == null) {
            return true;
        }

        int timestampComparison =
                first.getTimestamp().compareTo(
                        second.getTimestamp()
                );

        if (timestampComparison != 0) {
            return timestampComparison > 0;
        }

        if (first.getId() == null) {
            return false;
        }

        if (second.getId() == null) {
            return true;
        }

        return first.getId() > second.getId();
    }
}