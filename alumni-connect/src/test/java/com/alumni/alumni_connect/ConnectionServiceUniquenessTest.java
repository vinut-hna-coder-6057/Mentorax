package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.Connection;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ConnectionRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.alumni.alumni_connect.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConnectionServiceUniquenessTest {
    private ConnectionRepository connections;
    private UserRepository users;
    private ConnectionService service;
    private User alice,bob;

    @BeforeEach void setup() {
        connections=mock(ConnectionRepository.class); users=mock(UserRepository.class);
        CurrentUserService current=mock(CurrentUserService.class);
        alice=new User("Alice","alice@example.com","hash","STUDENT","APPROVED");
        bob=new User("Bob","bob@example.com","hash","ALUMNI","APPROVED");
        setId(alice,1L); setId(bob,2L);
        when(current.requireUser()).thenReturn(alice);
        when(users.findByIdInOrderByIdAsc(anyList())).thenReturn(List.of(alice,bob));
        service=new ConnectionService(connections,users,current);
    }
    @Test void sameDirectionExistingPairReturnsConflict() {
        when(connections.findByRequester_IdAndReceiver_Id(1L,2L)).thenReturn(Optional.of(new Connection()));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.request(2L)).getStatusCode());
    }
    @Test void oppositeDirectionExistingPairReturnsConflict() {
        when(connections.findByRequester_IdAndReceiver_Id(2L,1L)).thenReturn(Optional.of(new Connection()));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.request(2L)).getStatusCode());
    }
    @Test void databaseUniqueRaceBecomesConflict() {
        when(connections.saveAndFlush(any(Connection.class))).thenThrow(new DataIntegrityViolationException("unique pair"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,()->service.request(2L)).getStatusCode());
    }
    private static void setId(User user,long id) {
        try { var field=User.class.getDeclaredField("id"); field.setAccessible(true); field.set(user,id); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
