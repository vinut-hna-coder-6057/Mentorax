package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.*;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class KnowledgeConversationService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final GroundedKnowledgeAnswerService answers;
    private final KnowledgeContributionService contributions;
    private final UnansweredQuestionService unanswered;

    public KnowledgeConversationService(JdbcTemplate jdbc, CurrentUserService currentUser,
            GroundedKnowledgeAnswerService answers, KnowledgeContributionService contributions,
            UnansweredQuestionService unanswered) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.answers = answers;
        this.contributions = contributions;
        this.unanswered = unanswered;
    }

    @Transactional
    public CompanionConversationResponse create(ConversationCreateRequest request) {
        User student = requireStudent();
        String title = request == null ? null : request.title();
        if (title == null || title.isBlank()) title = "Knowledge question";
        long id = insertId("INSERT INTO knowledge_companion_conversations (student_user_id, title) VALUES (?, ?)",
                student.getId(), title.trim());
        return getConversationRow(id, student.getId());
    }

    @Transactional(readOnly = true)
    public List<CompanionConversationResponse> list(int page, int size) {
        User student = requireStudent();
        int bounded = Math.max(1, Math.min(size, 50));
        return jdbc.query("SELECT id,title,created_at,updated_at FROM knowledge_companion_conversations WHERE student_user_id=? ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, n) -> new CompanionConversationResponse(rs.getLong("id"), rs.getString("title"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()),
                student.getId(), bounded, (long) Math.max(0, page) * bounded);
    }

    @Transactional
    public void delete(long conversationId) {
        User student = requireStudent();
        requireConversation(conversationId, student.getId());
        jdbc.update("DELETE FROM knowledge_companion_message_sources WHERE message_id IN (SELECT id FROM knowledge_companion_messages WHERE conversation_id=?)", conversationId);
        jdbc.update("DELETE FROM knowledge_companion_messages WHERE conversation_id=?", conversationId);
        jdbc.update("DELETE FROM knowledge_companion_conversations WHERE id=? AND student_user_id=?", conversationId, student.getId());
    }

    @Transactional(readOnly = true)
    public List<CompanionMessageResponse> messages(long conversationId, int page, int size) {
        User student = requireStudent();
        requireConversation(conversationId, student.getId());
        int bounded = Math.max(1, Math.min(size, 50));
        List<MessageRow> rows = jdbc.query("""
                SELECT id, speaker, content, evidence_status, answer_mode, created_at
                FROM knowledge_companion_messages WHERE conversation_id=?
                ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> new MessageRow(rs.getLong("id"), rs.getString("speaker"), rs.getString("content"),
                rs.getString("evidence_status"), rs.getString("answer_mode"), rs.getTimestamp("created_at").toLocalDateTime()),
                conversationId, bounded, (long) Math.max(0, page) * bounded);
        Collections.reverse(rows);
        if (rows.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(rows.size(), "?"));
        List<Object> ids = rows.stream().map(MessageRow::id).map(x -> (Object) x).toList();
        Map<Long, List<SourceRow>> sources = new HashMap<>();
        jdbc.query("""
                SELECT s.message_id,s.contribution_id,s.version_number,v.title,v.category
                FROM knowledge_companion_message_sources s
                JOIN knowledge_contribution_versions v ON v.contribution_id=s.contribution_id AND v.version_number=s.version_number
                WHERE s.message_id IN (
                """ + marks + ") ORDER BY s.message_id,s.relevance_score DESC", rs -> {
            sources.computeIfAbsent(rs.getLong("message_id"), ignored -> new ArrayList<>()).add(
                    new SourceRow(rs.getLong("contribution_id"), rs.getInt("version_number"), rs.getString("title"), rs.getString("category")));
        }, ids.toArray());
        List<KnowledgeContributionService.ContributionVersionRef> refs = sources.values().stream().flatMap(Collection::stream)
                .map(s -> new KnowledgeContributionService.ContributionVersionRef(s.id(), s.version())).distinct().limit(250).toList();
        Set<String> eligible = contributions.recheckAiEligible(refs).stream()
                .map(c -> c.id() + ":" + c.version()).collect(java.util.stream.Collectors.toSet());
        return rows.stream().map(row -> {
            List<SourceRow> sourceRows = sources.getOrDefault(row.id(), List.of());
            boolean stale = "ASSISTANT".equals(row.speaker()) && !sourceRows.isEmpty()
                    && sourceRows.stream().anyMatch(s -> !eligible.contains(s.id() + ":" + s.version()));
            String content = stale ? "This earlier answer is no longer available because one or more source contributions are no longer eligible." : row.content();
            List<CompanionMessageResponse.SourceRef> references = sourceRows.stream().map(s -> {
                boolean current = eligible.contains(s.id() + ":" + s.version());
                return new CompanionMessageResponse.SourceRef(s.id(), s.version(), current ? KnowledgeContentSanitizer.redact(s.title()) : null,
                        current ? s.category() : null, current);
            }).toList();
            return new CompanionMessageResponse(row.id(), row.speaker(), content,
                    stale ? "SOURCE_REVOKED" : row.evidenceStatus(), row.answerMode(), row.createdAt(), references,
                    null, List.of());
        }).toList();
    }

    @Transactional
    public List<CompanionMessageResponse> ask(long conversationId, ConversationQuestionRequest request) {
        User student = requireStudent();
        requireConversation(conversationId, student.getId());
        String question = request.content().trim();
        LocalDateTime now = LocalDateTime.now();
        insertId("INSERT INTO knowledge_companion_messages (conversation_id,speaker,content,created_at) VALUES (?, 'USER', ?, ?)",
                conversationId, question, now);
        List<String> previous = jdbc.query("SELECT content FROM knowledge_companion_messages WHERE conversation_id=? AND speaker='USER' ORDER BY created_at DESC,id DESC LIMIT 3",
                (rs, n) -> rs.getString(1), conversationId);
        String contextualQuery = String.join(" ", previous) + " " + question;
        KnowledgeAnswerResponse answer = answers.answer(contextualQuery.length() > 500
                ? contextualQuery.substring(contextualQuery.length() - 500) : contextualQuery);
        if ("INSUFFICIENT_EVIDENCE".equals(answer.evidenceStatus())
                || "CONFLICTING_REPORTS".equals(answer.evidenceStatus())) unanswered.record(student.getId(), question);
        String answerContent = answer.followUpReason() == null ? answer.answer()
                : answer.answer() + "\n\nHuman follow-up may help: " + answer.followUpReason();
        long answerId = insertId("INSERT INTO knowledge_companion_messages (conversation_id,speaker,content,evidence_status,answer_mode,created_at) VALUES (?, 'ASSISTANT', ?, ?, ?, ?)",
                conversationId, answerContent, answer.evidenceStatus(), answer.answerMode(), now.plusNanos(1));
        for (var source : answer.sources()) jdbc.update("""
                INSERT INTO knowledge_companion_message_sources (message_id,contribution_id,version_number,relevance_score)
                VALUES (?,?,?,?)
                """, answerId, source.contributionId(), source.version(), source.relevance());
        jdbc.update("UPDATE knowledge_companion_conversations SET updated_at=? WHERE id=? AND student_user_id=?",
                now, conversationId, student.getId());
        List<CompanionMessageResponse> result = new ArrayList<>(messages(conversationId, 0, 50));
        if (!result.isEmpty()) {
            int last = result.size() - 1;
            var current = result.get(last);
            List<CompanionMessageResponse.Mentor> mentors = answer.mentors().stream()
                    .map(m -> new CompanionMessageResponse.Mentor(m.id(), m.displayName(), m.jobTitle(), m.company(),
                            m.relevance(), m.uncertainty())).toList();
            result.set(last, new CompanionMessageResponse(current.id(), current.speaker(), current.content(),
                    current.evidenceStatus(), current.answerMode(), current.createdAt(), current.sources(),
                    answer.followUpReason(), mentors));
        }
        return result;
    }

    private User requireStudent() {
        User user = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(user.getRole()) || !"APPROVED".equalsIgnoreCase(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved student access required");
        return user;
    }
    private void requireConversation(long id, long owner) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_companion_conversations WHERE id=? AND student_user_id=?)", Boolean.class, id, owner)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
    }
    private CompanionConversationResponse getConversationRow(long id, long owner) {
        return jdbc.query("SELECT id,title,created_at,updated_at FROM knowledge_companion_conversations WHERE id=? AND student_user_id=?",
                (rs, n) -> new CompanionConversationResponse(rs.getLong("id"), rs.getString("title"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()), id, owner)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
    }
    private long insertId(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Map<String, Object> keys = Objects.requireNonNull(holder.getKeys());
        Object id = keys.entrySet().stream().filter(e -> "id".equalsIgnoreCase(e.getKey()))
                .map(Map.Entry::getValue).findFirst().orElseGet(() -> keys.values().stream()
                        .filter(Number.class::isInstance).findFirst().orElseThrow());
        return ((Number) id).longValue();
    }
    private record MessageRow(long id, String speaker, String content, String evidenceStatus,
                              String answerMode, LocalDateTime createdAt) {}
    private record SourceRow(long id, int version, String title, String category) {}
}
