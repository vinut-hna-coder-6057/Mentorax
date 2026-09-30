package com.alumni.alumni_connect.dto;
import com.alumni.alumni_connect.entity.Message;
import java.time.LocalDateTime;
public record MessageResponse(Long id, String senderEmail, String receiverEmail, String content, LocalDateTime timestamp, Long conversationId) {
    public static MessageResponse from(Message m) { return new MessageResponse(m.getId(),m.getSenderEmail(),m.getReceiverEmail(),m.getContent(),m.getTimestamp(),m.getConversationId()); }
}
