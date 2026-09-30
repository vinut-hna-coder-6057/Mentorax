package com.alumni.alumni_connect.dto;
import com.alumni.alumni_connect.entity.UserSkill;
public record UserSkillResponse(Long userId, Long skillId, String skillName, String proficiency) {
    public static UserSkillResponse from(UserSkill s) { return new UserSkillResponse(s.getUser().getId(),s.getSkill().getId(),s.getSkill().getName(),s.getProficiency()); }
}
