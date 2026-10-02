package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ConversationParticipantRepository;
import com.alumni.alumni_connect.repository.ConversationRepository;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.service.MessageService;
import com.alumni.alumni_connect.service.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationPaginationTest {
    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void conversationPageAndSizeAreBoundedBeforeRepositoryQuery() {
        String email = "alice@example.test";
        User user = new User("Alice", email, "encoded", "STUDENT", "APPROVED");
        ReflectionTestUtils.setField(user, "id", 12L);
        UserRepository users = mock(UserRepository.class);
        MessageRepository messages = mock(MessageRepository.class);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        when(messages.findLatestMessagesForParticipant(eq(12L), eq(email), any(Pageable.class)))
                .thenReturn(List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null));
        MessageService service = new MessageService(
                mock(SimpMessagingTemplate.class), messages, mock(NotificationService.class), users,
                mock(ConversationRepository.class), mock(ConversationParticipantRepository.class));

        service.getConversations(-3, 1000);

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(messages).findLatestMessagesForParticipant(eq(12L), eq(email), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
    }
}
