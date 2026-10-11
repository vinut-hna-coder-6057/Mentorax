package com.alumni.alumni_connect.security;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

@Component
public class StompJwtChannelInterceptor implements ChannelInterceptor {
    private static final Set<String> PRIVATE_SUBSCRIPTIONS = Set.of(
            "/user/queue/messages",
            "/user/queue/notifications");

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final WebSocketMessageRateLimiter rateLimiter;

    public StompJwtChannelInterceptor(JwtUtil jwtUtil, UserRepository userRepository,
                                      WebSocketMessageRateLimiter rateLimiter) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ") || !jwtUtil.validateToken(header.substring(7))) {
                throw new IllegalArgumentException("Valid JWT is required for WebSocket connection");
            }
            String token = header.substring(7);
            String email = jwtUtil.extractEmail(token);
            String role = jwtUtil.extractRole(token);
            var user = email == null ? null : userRepository.findByEmail(email).orElse(null);
            if (user == null || role == null
                    || !role.equalsIgnoreCase(user.getRole())
                    || !"APPROVED".equalsIgnoreCase(user.getStatus())) {
                throw new IllegalArgumentException("Authenticated account is not active");
            }
            accessor.setUser(new UsernamePasswordAuthenticationToken(email, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            if (accessor.getUser() == null) {
                throw new IllegalArgumentException("Authentication is required for this WebSocket operation");
            }
            String email = accessor.getUser().getName();
            var user = userRepository.findByEmail(email).orElse(null);
            if (user == null || !"APPROVED".equalsIgnoreCase(user.getStatus())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authenticated account is not active");
            }
            rateLimiter.check(email, accessor.getSessionId());
        } else if (StompCommand.DISCONNECT.equals(accessor.getCommand())) {
            rateLimiter.removeSession(accessor.getSessionId());
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            if (accessor.getUser() == null) {
                throw new IllegalArgumentException("Authentication is required for this WebSocket operation");
            }
            String destination = accessor.getDestination();
            if (destination == null || !PRIVATE_SUBSCRIPTIONS.contains(destination)) {
                throw new IllegalArgumentException(
                        "Subscription is not an allowed private user destination");
            }
        }
        return message;
    }
}
