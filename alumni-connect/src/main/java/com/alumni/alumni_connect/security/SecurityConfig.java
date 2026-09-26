package com.alumni.alumni_connect.security;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.context.annotation.Bean;

import org.springframework.context.annotation.Configuration;

import org.springframework.http.HttpMethod;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

import org.springframework.security.config.http.SessionCreationPolicy;

import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@Configuration
    
@EnableMethodSecurity
    
public class SecurityConfig {

    private static final String JSON_ERROR = "{\"error\":\"%s\"}";

    @Bean

    public SecurityFilterChain filterChain(

            HttpSecurity http

            , JwtFilter jwtFilter

    ) throws Exception {

        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(JSON_ERROR.formatted("Authentication required"));
        };
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(JSON_ERROR.formatted("Access denied"));
        };

        http

                // =====================================
                // DISABLE CSRF
                // =====================================

                .csrf(csrf -> csrf.disable())

                // =====================================
                // ENABLE CORS
                // =====================================

                .cors(cors -> {})

                // =====================================
                // STATELESS SESSION
                // =====================================

                .sessionManagement(session ->

                        session.sessionCreationPolicy(

                                SessionCreationPolicy.STATELESS
                        )
                )

                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                // =====================================
                // AUTHORIZATION
                // =====================================

                .authorizeHttpRequests(auth -> auth

                        // =================================
                        // PUBLIC ROUTES
                        // =================================

                        .requestMatchers(

                                "/",

                                "/login",

                                "/signup",

                                "/forgot-password",

                                "/verify-otp",

                                "/reset-password",
                                
                                "/verify-email"

                        ).permitAll()

                        .requestMatchers(HttpMethod.GET, "/alumni", "/alumni/approved").permitAll()

                        .requestMatchers("/alumni/**").hasRole("ADMIN")

                        // =================================
                        // EVENTS
                        // =================================

                        .requestMatchers(
        HttpMethod.GET,
        "/events"
        ).permitAll()

        .requestMatchers(
        "/events/all",
        "/events/approve/**",
        "/events/reject/**"
        ).hasRole("ADMIN")

        .requestMatchers(
        "/events/**"
        ).authenticated()

                        // =================================
                        // CHAT + WEBSOCKET
                        // =================================
                        .requestMatchers(
    "/messages/**",
    "/conversations/**"
).authenticated()

                        // =================================
                        // USERS + STUDENTS
                        // =================================

   .requestMatchers(
    "/users/**",
    "/students",
    "/students/**",
    "/notifications/**"
).authenticated()

.requestMatchers("/connections/**").authenticated()
.requestMatchers("/skills/**").authenticated()

.requestMatchers("/approve/**").hasRole("ADMIN")

                        // =================================
                        // H2 CONSOLE
                        // =================================

                        .requestMatchers(

                                "/h2-console/**"

                        ).permitAll()

                        // =================================
                        // OPTIONS REQUESTS
                        // =================================

                        .requestMatchers(

                                HttpMethod.OPTIONS,

                                "/**"

                        ).permitAll()

                        // =================================
                        // EVERYTHING ELSE
                        // =================================

                        .anyRequest().authenticated()
                );

        // =====================================
        // H2 CONSOLE FIX
        // =====================================

        http.headers(headers ->

                headers.frameOptions(

                        frame -> frame.disable()
                )
        );

        http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
