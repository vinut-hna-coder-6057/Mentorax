package com.alumni.alumni_connect.dto;
import com.alumni.alumni_connect.entity.Connection;
import java.time.LocalDateTime;
public record ConnectionResponse(Long id, NetworkProfileResponse requester, NetworkProfileResponse receiver, String status, LocalDateTime createdAt, LocalDateTime respondedAt) {
    public static ConnectionResponse from(Connection c) {
        boolean shareEmail = "ACCEPTED".equalsIgnoreCase(c.getStatus());
        return new ConnectionResponse(
                c.getId(),
                NetworkProfileResponse.from(c.getRequester(), shareEmail),
                NetworkProfileResponse.from(c.getReceiver(), shareEmail),
                c.getStatus(),
                c.getCreatedAt(),
                c.getRespondedAt());
    }
}
