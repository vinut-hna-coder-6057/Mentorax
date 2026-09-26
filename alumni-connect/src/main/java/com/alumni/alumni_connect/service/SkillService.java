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
import java.util.List;

@Service
public class SkillService {
    private final SkillRepository skillRepository;
    private final UserSkillRepository userSkillRepository;
    private final CurrentUserService currentUser;

    public SkillService(SkillRepository skillRepository, UserSkillRepository userSkillRepository, CurrentUserService currentUser) {
        this.skillRepository = skillRepository;
        this.userSkillRepository = userSkillRepository;
        this.currentUser = currentUser;
    }

    public List<UserSkill> mine() { return userSkillRepository.findByUser_Id(currentUser.requireUser().getId()); }

    @Transactional
    public UserSkill add(String name, String proficiency) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required");
        User user = currentUser.requireUser();
        Skill skill = skillRepository.findByNameIgnoreCase(name.trim()).orElseGet(() -> skillRepository.save(new Skill(name.trim())));
        UserSkillId id = new UserSkillId(user.getId(), skill.getId());
        return userSkillRepository.findById(id).orElseGet(() -> userSkillRepository.save(new UserSkill(user, skill, proficiency)));
    }

    @Transactional
    public void remove(Long skillId) { userSkillRepository.deleteByUser_IdAndSkill_Id(currentUser.requireUser().getId(), skillId); }
}

