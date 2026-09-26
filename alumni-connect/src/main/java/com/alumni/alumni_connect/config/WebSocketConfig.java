package com.alumni.alumni_connect.config;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.messaging.simp.config.MessageBrokerRegistry;

import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;

import org.springframework.web.socket.config.annotation.StompEndpointRegistry;

import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.ChannelRegistration;

@Configuration

@EnableWebSocketMessageBroker

public class WebSocketConfig
        implements WebSocketMessageBrokerConfigurer {

    private final StompJwtChannelInterceptor stompJwtChannelInterceptor;
    private final String[] allowedOrigins;

    public WebSocketConfig(
            StompJwtChannelInterceptor stompJwtChannelInterceptor,
            @Value("${app.websocket.allowed-origins}") String allowedOrigins
    ) {
        this.stompJwtChannelInterceptor = stompJwtChannelInterceptor;
        this.allowedOrigins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    @Override

    public void configureMessageBroker(

            MessageBrokerRegistry registry

    ) {

        // WHERE CLIENT SUBSCRIBES

        registry.enableSimpleBroker(
                "/topic", "/queue"
        );

        registry.setUserDestinationPrefix("/user");

        // WHERE CLIENT SENDS

        registry.setApplicationDestinationPrefixes(
                "/app"
        );
    }

    @Override

    public void registerStompEndpoints(

            StompEndpointRegistry registry

    ) {

        registry

                .addEndpoint("/chat")

                .setAllowedOriginPatterns(allowedOrigins)

                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompJwtChannelInterceptor);
    }
}

