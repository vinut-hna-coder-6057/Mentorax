package com.alumni.alumni_connect.controller;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/connections")
public class ConnectionController {
    private final ConnectionService service;
    public ConnectionController(ConnectionService service) { this.service = service; }
    @GetMapping public List<Connection> mine() { return service.mine(); }
    @PostMapping("/{receiverId}") public Connection request(@PathVariable Long receiverId) { return service.request(receiverId); }
    @PutMapping("/{id}") public Connection respond(@PathVariable Long id, @RequestParam String status) { return service.respond(id, status); }
}

