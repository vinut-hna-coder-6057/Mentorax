package com.alumni.alumni_connect.dto;
import com.alumni.alumni_connect.entity.Notification;
import java.time.LocalDateTime;
public record NotificationResponse(Long id, String email, String message, boolean isRead, String type, String linkUrl, LocalDateTime timestamp) {
    public static NotificationResponse from(Notification n) { return new NotificationResponse(n.getId(), n.getEmail(), n.getMessage(), n.isRead(), n.getType(), n.getLinkUrl(), n.getTimestamp()); }
}
