package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import jakarta.persistence.*;

@Entity
@Table(name = "user_skills")
@IdClass(UserSkillId.class)
public class UserSkill {
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "skill_id")
    private Skill skill;

    @Column(length = 64)
    private String proficiency;

    protected UserSkill() { }
    public UserSkill(User user, Skill skill, String proficiency) {
        this.user = user;
        this.skill = skill;
        this.proficiency = proficiency;
    }
    public User getUser() { return user; }
    public Skill getSkill() { return skill; }
    public String getProficiency() { return proficiency; }
}

