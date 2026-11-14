package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.FreshnessSettingsRequest;
import com.alumni.alumni_connect.dto.knowledge.FreshnessStatusResponse;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeFreshnessService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final NotificationService notifications;

    public KnowledgeFreshnessService(JdbcTemplate jdbc, CurrentUserService currentUser, NotificationService notifications) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public FreshnessStatusResponse get(long id) {
        User owner = requireOwner();
        return jdbc.query("SELECT id,freshness_interval_days,freshness_reminders_enabled,last_confirmed_at,reminder_due_at,last_reminder_sent_at FROM knowledge_contributions WHERE id=? AND owner_user_id=?",
                (rs, n) -> new FreshnessStatusResponse(rs.getLong("id"), rs.getInt("freshness_interval_days"),
                        rs.getBoolean("freshness_reminders_enabled"), time(rs, "last_confirmed_at"),
                        time(rs, "reminder_due_at"), time(rs, "last_reminder_sent_at")), id, owner.getId())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contribution not found"));
    }

    @Transactional
    public FreshnessStatusResponse configure(long id, FreshnessSettingsRequest request) {
        User owner = requireOwner();
        if (request.intervalDays() != 90 && request.intervalDays() != 180)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Freshness interval must be 90 or 180 days");
        if (!exists(id, owner.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contribution not found");
        LocalDateTime confirmed = jdbc.query("SELECT last_confirmed_at FROM knowledge_contributions WHERE id=? AND owner_user_id=?",
                rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toLocalDateTime() : null, id, owner.getId());
        LocalDateTime due = Boolean.TRUE.equals(request.remindersEnabled()) && confirmed != null
                ? confirmed.plusDays(request.intervalDays()) : null;
        jdbc.update("UPDATE knowledge_contributions SET freshness_interval_days=?,freshness_reminders_enabled=?,reminder_due_at=?,last_reminder_sent_at=NULL WHERE id=? AND owner_user_id=?",
                request.intervalDays(), request.remindersEnabled(), due, id, owner.getId());
        return get(id);
    }

    @Transactional
    public FreshnessStatusResponse confirm(long id) {
        User owner = requireOwner();
        boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM knowledge_contributions c
                JOIN knowledge_contribution_versions v ON v.contribution_id=c.id AND v.version_number=c.published_version
                WHERE c.id=? AND c.owner_user_id=? AND c.status='PUBLISHED'
                  AND v.student_visibility_consent=TRUE AND v.ai_retrieval_consent=TRUE)
                """, Boolean.class, id, owner.getId()));
        if (!eligible) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only currently eligible published content can be confirmed");
        LocalDateTime now = LocalDateTime.now();
        int days = jdbc.queryForObject("SELECT freshness_interval_days FROM knowledge_contributions WHERE id=?", Integer.class, id);
        jdbc.update("UPDATE knowledge_contributions SET last_confirmed_at=?,reminder_due_at=?,last_reminder_sent_at=NULL WHERE id=? AND owner_user_id=?",
                now, now.plusDays(days), id, owner.getId());
        Integer version = jdbc.queryForObject("SELECT published_version FROM knowledge_contributions WHERE id=?", Integer.class, id);
        jdbc.update("INSERT INTO knowledge_contribution_audit (contribution_id,version_number,actor_user_id,event_type,student_visibility_consent,ai_retrieval_consent) VALUES (?,?,?,'FRESHNESS_CONFIRMED',TRUE,TRUE)",
                id, version, owner.getId());
        return get(id);
    }

    @Transactional
    public int dispatchDueReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Due> due = jdbc.query("""
                SELECT c.id,c.owner_user_id,u.email,v.title FROM knowledge_contributions c
                JOIN users u ON u.id=c.owner_user_id
                JOIN knowledge_contribution_versions v ON v.contribution_id=c.id AND v.version_number=c.published_version
                WHERE c.status='PUBLISHED' AND c.freshness_reminders_enabled=TRUE AND c.reminder_due_at<=?
                  AND (c.last_reminder_sent_at IS NULL OR c.last_reminder_sent_at<c.reminder_due_at)
                  AND v.student_visibility_consent=TRUE AND v.ai_retrieval_consent=TRUE
                  AND u.role='ALUMNI' AND u.status='APPROVED'
                ORDER BY c.reminder_due_at,c.id LIMIT 100
                """, (rs, n) -> new Due(rs.getLong("id"), rs.getString("email"), rs.getString("title")), now);
        int sent = 0;
        for (Due item : due) {
            int claimed = jdbc.update("UPDATE knowledge_contributions SET last_reminder_sent_at=? WHERE id=? AND freshness_reminders_enabled=TRUE AND reminder_due_at<=? AND (last_reminder_sent_at IS NULL OR last_reminder_sent_at<reminder_due_at)",
                    now, item.id(), now);
            if (claimed == 1) {
                notifications.sendNotification(item.email(), "Please review whether your alumni knowledge contribution ‘"
                        + item.title() + "’ is still current. You may confirm, update, change its consent, or withdraw it.",
                        "KNOWLEDGE_FRESHNESS", "/alumni/knowledge");
                sent++;
            }
        }
        return sent;
    }

    private boolean exists(long id, long owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_contributions WHERE id=? AND owner_user_id=?)", Boolean.class, id, owner));
    }
    private User requireOwner() {
        User user = currentUser.requireUser();
        if (!"ALUMNI".equalsIgnoreCase(user.getRole()) || !"APPROVED".equalsIgnoreCase(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved alumni only");
        return user;
    }
    private static LocalDateTime time(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        var value = rs.getTimestamp(field);
        return value == null ? null : value.toLocalDateTime();
    }
    private record Due(long id, String email, String title) {}
}
