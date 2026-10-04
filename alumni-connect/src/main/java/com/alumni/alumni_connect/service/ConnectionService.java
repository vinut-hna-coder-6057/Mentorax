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
import org.springframework.dao.DataIntegrityViolationException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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
        Optional<Connection> previousRequest =
                repository.findByRequester_IdAndReceiver_Id(lockedRequester.getId(), receiverId);
        if (previousRequest.isPresent()
                && "REJECTED".equalsIgnoreCase(previousRequest.get().getStatus())) {
            Connection retry = previousRequest.get();
            retry.setStatus("PENDING");
            retry.setCreatedAt(LocalDateTime.now());
            retry.setRespondedAt(null);
            return repository.saveAndFlush(retry);
        }
        if (previousRequest.isPresent()
                || repository.findByRequester_IdAndReceiver_Id(receiverId, lockedRequester.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection already exists");
        }
        Connection connection = new Connection();
        connection.setRequester(lockedRequester);
        connection.setReceiver(receiver);
        connection.setStatus("PENDING");
        try {
            return repository.saveAndFlush(connection);
        } catch (DataIntegrityViolationException race) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection already exists");
        }
    }

    @Transactional
    public Connection respond(Long id, String status) {
        Connection connection = repository.findByIdForUpdateWithUsers(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        User caller = currentUser.requireUser();

        if (connection.getReceiver() == null || !caller.getId().equals(connection.getReceiver().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the recipient can respond");
        }

        String normalizedStatus = status == null ? null : status.trim().toUpperCase();
        if (normalizedStatus == null
                || !("ACCEPTED".equals(normalizedStatus)
                || "REJECTED".equals(normalizedStatus)
                || "BLOCKED".equals(normalizedStatus))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid connection transition");
        }
        if (!"PENDING".equalsIgnoreCase(connection.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection request has already been processed");
        }

        connection.setStatus(normalizedStatus);
        connection.setRespondedAt(LocalDateTime.now());
        return repository.save(connection);
    }

    public List<Connection> mine() {
        return mine(0,50);
    }
    public List<Connection> mine(int page,int size) {
        User caller = currentUser.requireUser();
        Pageable pageable=PageRequest.of(Math.max(0,page),Math.max(1,Math.min(size,100)), Sort.by("createdAt").descending());
        return repository.findByRequester_IdOrReceiver_Id(caller.getId(), caller.getId(),pageable);
    }
}
