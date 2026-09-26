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
@RequestMapping("/skills")
public class SkillController {
    private final SkillService service;
    public SkillController(SkillService service) { this.service = service; }
    @GetMapping("/me") public List<UserSkill> mine() { return service.mine(); }
    @PostMapping public UserSkill add(@RequestParam String name, @RequestParam(required = false) String proficiency) { return service.add(name, proficiency); }
    @DeleteMapping("/{skillId}") public void remove(@PathVariable Long skillId) { service.remove(skillId); }
}

