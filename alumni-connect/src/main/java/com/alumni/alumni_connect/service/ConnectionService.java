package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Arrays;

@Service
public class ConnectionService {
    private final ConnectionRepository repository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUser;

    public ConnectionService(ConnectionRepository repository, UserRepository userRepository, CurrentUserService currentUser) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @Transactional
    public Connection request(Long receiverId) {
        User requester = currentUser.requireUser();
        if (requester.getId().equals(receiverId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot connect to yourself");
        List<User> pair = userRepository.findByIdInOrderByIdAsc(Arrays.asList(requester.getId(), receiverId));
        if (pair.size() != 2) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        }
        User lockedRequester = pair.stream().filter(user -> user.getId().equals(requester.getId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required"));
        User receiver = pair.stream().filter(user -> user.getId().equals(receiverId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (repository.findByRequester_IdAndReceiver_Id(lockedRequester.getId(), receiverId).isPresent()
                || repository.findByRequester_IdAndReceiver_Id(receiverId, lockedRequester.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection already exists");
        }
        Connection connection = new Connection();
        connection.setRequester(lockedRequester);
        connection.setReceiver(receiver);
        connection.setStatus("PENDING");
        return repository.save(connection);
    }

    @Transactional
    public Connection respond(Long id, String status) {
        Connection connection = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        User caller = currentUser.requireUser();
        if (!caller.getId().equals(connection.getReceiver().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the recipient can respond");
        }
        if (!"PENDING".equals(connection.getStatus()) || !("ACCEPTED".equals(status) || "REJECTED".equals(status) || "BLOCKED".equals(status))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid connection transition");
        }
        connection.setStatus(status);
        connection.setRespondedAt(LocalDateTime.now());
        return repository.save(connection);
    }

    public List<Connection> mine() {
        User caller = currentUser.requireUser();
        return repository.findByRequester_IdOrReceiver_Id(caller.getId(), caller.getId());
    }
}

