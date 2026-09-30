package com.alumni.alumni_connect.dto;
import com.alumni.alumni_connect.entity.Connection;
import java.time.LocalDateTime;
public record ConnectionResponse(Long id, UserProfileResponse requester, UserProfileResponse receiver, String status, LocalDateTime createdAt, LocalDateTime respondedAt) {
    public static ConnectionResponse from(Connection c) { return new ConnectionResponse(c.getId(), UserProfileResponse.from(c.getRequester()), UserProfileResponse.from(c.getReceiver()), c.getStatus(), c.getCreatedAt(), c.getRespondedAt()); }
}
