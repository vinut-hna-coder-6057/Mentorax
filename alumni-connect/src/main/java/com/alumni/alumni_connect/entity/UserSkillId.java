package com.alumni.alumni_connect.entity;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import java.io.Serializable;
import java.util.Objects;

public class UserSkillId implements Serializable {
    private Long user;
    private Long skill;

    public UserSkillId() { }

    public UserSkillId(Long user, Long skill) {
        this.user = user;
        this.skill = skill;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserSkillId that)) return false;
        return Objects.equals(user, that.user) && Objects.equals(skill, that.skill);
    }

    @Override
    public int hashCode() { return Objects.hash(user, skill); }
}
