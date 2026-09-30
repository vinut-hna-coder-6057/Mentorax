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
    @GetMapping @org.springframework.transaction.annotation.Transactional(readOnly = true) public List<ConnectionResponse> mine(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) { return service.mine(page,size).stream().map(ConnectionResponse::from).toList(); }
    @PostMapping("/{receiverId}") public ConnectionResponse request(@PathVariable Long receiverId) { return ConnectionResponse.from(service.request(receiverId)); }
    @PutMapping("/{id}") public ConnectionResponse respond(@PathVariable Long id, @RequestParam String status) { return ConnectionResponse.from(service.respond(id, status)); }
}

