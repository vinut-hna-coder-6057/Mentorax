package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.InboxAssistantResponse;
import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InboxAssistantServiceTest {
    private MessageRepository messages;
    private CurrentUserService currentUser;
    private RestTemplate rest;
    private InboxAssistantService service;
    private User alumni;

    @BeforeEach
    void setUp() {
        messages = mock(MessageRepository.class);
        currentUser = mock(CurrentUserService.class);
        rest = mock(RestTemplate.class);
        service = new InboxAssistantService(messages, currentUser, rest, new ObjectMapper());
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "apiToken", "");
        alumni = new User();
        alumni.setName("Alumni");
        alumni.setEmail("alumni@example.test");
        alumni.setRole("ALUMNI");
        alumni.setStatus("APPROVED");
        when(currentUser.requireUser()).thenReturn(alumni);
    }

    @Test
    void onlyAnalyzesMessageReceivedByAuthenticatedAlumni() {
        Message received = new Message("student@example.test", "alumni@example.test", "Can you advise on my career?", null);
        when(messages.findById(8L)).thenReturn(Optional.of(received));
        InboxAssistantResponse result = service.analyzeIncomingMessage(8L);
        assertFalse(result.available());
        verifyNoInteractions(rest);

        Message sent = new Message("alumni@example.test", "student@example.test", "Thanks", null);
        when(messages.findById(9L)).thenReturn(Optional.of(sent));
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.analyzeIncomingMessage(9L)).getStatusCode().value());
    }

    @Test
    void missingMessageIsNotFound() {
        when(messages.findById(44L)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.analyzeIncomingMessage(44L)).getStatusCode().value());
    }

    @Test
    void nonAlumniCannotUseAssistant() {
        alumni.setRole("STUDENT");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> service.analyzeIncomingMessage(8L)).getStatusCode().value());
        verifyNoInteractions(messages, rest);
    }

    @Test
    void pythonOutageReturnsSafeUnavailableResult() {
        Message received = new Message("student@example.test", "alumni@example.test", "Career advice?", null);
        ReflectionTestUtils.setField(received, "id", 8L);
        when(messages.findById(8L)).thenReturn(Optional.of(received));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiToken", "test-only-secret");
        when(rest.exchange(anyString(), any(), any(), eq(Object.class)))
                .thenThrow(new ResourceAccessException("upstream detail must not escape"));
        InboxAssistantResponse result = service.analyzeIncomingMessage(8L);
        assertFalse(result.available());
        assertTrue(result.message().contains("temporarily unavailable"));
        verify(rest).exchange(anyString(), any(), any(), eq(Object.class));
    }

    @Test
    void malformedPythonOutputIsNotExposedAsAnAssistantResult() {
        Message received = new Message("student@example.test", "alumni@example.test", "Career advice?", null);
        ReflectionTestUtils.setField(received, "id", 8L);
        when(messages.findById(8L)).thenReturn(Optional.of(received));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiToken", "test-only-secret");
        when(rest.exchange(anyString(), eq(HttpMethod.POST), any(), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(java.util.Map.of("available", true, "category", "FORGED")));

        InboxAssistantResponse result = service.analyzeIncomingMessage(8L);

        assertFalse(result.available());
        assertTrue(result.message().contains("temporarily unavailable"));
    }

    @Test
    void pythonRouteFailureReturnsSafeUnavailableResult() {
        Message received = new Message("student@example.test", "alumni@example.test", "Career advice?", null);
        ReflectionTestUtils.setField(received, "id", 8L);
        when(messages.findById(8L)).thenReturn(Optional.of(received));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "apiToken", "test-only-secret");
        when(rest.exchange(anyString(), any(), any(), eq(Object.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        InboxAssistantResponse result = service.analyzeIncomingMessage(8L);

        assertFalse(result.available());
        assertTrue(result.message().contains("temporarily unavailable"));
    }
}
