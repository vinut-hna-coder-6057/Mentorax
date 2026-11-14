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
import java.util.stream.Collectors;

@Service
public class KnowledgeRoadmapService {
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final KnowledgeRetrievalService retrieval;
    private final KnowledgeContributionService contributions;

    public KnowledgeRoadmapService(JdbcTemplate jdbc, CurrentUserService currentUser,
                                   KnowledgeRetrievalService retrieval, KnowledgeContributionService contributions) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.retrieval = retrieval;
        this.contributions = contributions;
    }

    @Transactional
    public RoadmapResponse create(RoadmapCreateRequest request) {
        User student = requireStudent();
        String goal = request.goal().trim();
        var evidence = retrieval.retrieve(goal, null, 0, 5).sources();
        String evidenceStatus = evidence.isEmpty() ? "GENERAL_ONLY" : "MIXED_EVIDENCE";
        long id = insertId("INSERT INTO knowledge_roadmaps (student_user_id,title,goal,evidence_status) VALUES (?,?,?,?)",
                student.getId(), roadmapTitle(goal), goal, evidenceStatus);
        int position = 0;
        for (var source : evidence) {
            long item = insertId("INSERT INTO knowledge_roadmap_items (roadmap_id,position_index,phase,title,description,source_kind) VALUES (?,?,?,?,?,'ALUMNI_REPORTED')",
                    id, position++, "Review relevant reports", "Review an eligible alumni-reported example for this goal and compare it with your own preparation.",
                    "Source review: " + source.category());
            jdbc.update("INSERT INTO knowledge_roadmap_item_sources (item_id,contribution_id,version_number) VALUES (?,?,?)",
                    item, source.contributionId(), source.version());
        }
        if (evidence.isEmpty()) {
            addGeneralItem(id, position++, "Evidence availability", "No currently eligible alumni report matched this goal. The following are general suggestions, not alumni-reported guidance.");
        }
        addGeneralItem(id, position++, "Define your target", "For “" + goal + "”, write down the outcome you want and split it into smaller prerequisites. Estimated effort: 1–2 hours (general planning estimate; adjust to your experience).");
        addGeneralItem(id, position++, "Learn core topics", "List the concepts and skills relevant to your goal, study them in a manageable order, and note any gaps. Estimated effort: 4–6 hours (general estimate; adjust to your schedule).");
        addGeneralItem(id, position++, "Build a practical project", "Choose a small project related to your goal, define a finishable scope, and document your decisions. This is a general suggestion, not a claim about an employer requirement or alumni project. Estimated effort: 4–8 hours (general estimate; adjust to scope).");
        addGeneralItem(id, position++, "Complete practice tasks", "Apply the topics through exercises, review mistakes, and record questions for follow-up. Estimated effort: 2–4 hours (general estimate; adjust to your pace).");
        addGeneralItem(id, position++, "Prepare to explain your work", "If this goal involves interviews or applications, practise explaining your approach and prepare questions. No company-specific hiring criteria are inferred. Estimated effort: 1–3 hours (general estimate; adapt as needed).");
        addGeneralItem(id, position, "Review and adjust", "Review progress, seek feedback where useful, and choose the next steps based on what you learned. Estimated effort: 1 hour (general estimate; adapt to your circumstances).");
        return get(id);
    }

    @Transactional(readOnly = true)
    public List<RoadmapResponse> list() {
        User student = requireStudent();
        List<Long> ids = jdbc.query("SELECT id FROM knowledge_roadmaps WHERE student_user_id=? ORDER BY updated_at DESC,id DESC LIMIT 50",
                (rs, n) -> rs.getLong(1), student.getId());
        return ids.stream().map(this::get).toList();
    }

    @Transactional(readOnly = true)
    public RoadmapResponse get(long id) {
        User student = requireStudent();
        RoadmapBase base = jdbc.query("SELECT id,title,goal,evidence_status,created_at,updated_at FROM knowledge_roadmaps WHERE id=? AND student_user_id=?",
                (rs, n) -> new RoadmapBase(rs.getLong("id"), rs.getString("title"), rs.getString("goal"), rs.getString("evidence_status"),
                        rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()), id, student.getId())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Roadmap not found"));
        List<ItemBase> items = jdbc.query("SELECT id,position_index,phase,title,description,source_kind,progress_status FROM knowledge_roadmap_items WHERE roadmap_id=? ORDER BY position_index",
                (rs, n) -> new ItemBase(rs.getLong("id"), rs.getInt("position_index"), rs.getString("phase"), rs.getString("title"),
                        rs.getString("description"), rs.getString("source_kind"), rs.getString("progress_status")), id);
        if (items.isEmpty()) return new RoadmapResponse(base.id(), base.title(), base.goal(), base.evidenceStatus(), base.createdAt(), base.updatedAt(), List.of());
        String marks = String.join(",", Collections.nCopies(items.size(), "?"));
        List<Object> itemIds = items.stream().map(ItemBase::id).map(x -> (Object) x).toList();
        Map<Long, List<RoadmapSource>> sourceMap = new HashMap<>();
        jdbc.query("SELECT item_id,contribution_id,version_number FROM knowledge_roadmap_item_sources WHERE item_id IN (" + marks + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> sourceMap.computeIfAbsent(rs.getLong("item_id"), ignored -> new ArrayList<>())
                        .add(new RoadmapSource(rs.getLong("contribution_id"), rs.getInt("version_number"))), itemIds.toArray());
        List<KnowledgeContributionService.ContributionVersionRef> refs = sourceMap.values().stream().flatMap(Collection::stream)
                .map(s -> new KnowledgeContributionService.ContributionVersionRef(s.contributionId(), s.version())).distinct().toList();
        Map<String, KnowledgeContributionResponse> current = contributions.recheckAiEligible(refs).stream()
                .collect(Collectors.toMap(c -> c.id() + ":" + c.version(), c -> c));
        List<RoadmapResponse.Item> result = items.stream().map(item -> new RoadmapResponse.Item(item.id(), item.position(),
                item.phase(), item.title(), item.description(), item.sourceKind(), item.status(),
                sourceMap.getOrDefault(item.id(), List.of()).stream().map(source -> {
                    KnowledgeContributionResponse c = current.get(source.contributionId() + ":" + source.version());
                    return new RoadmapResponse.Source(source.contributionId(), source.version(), c == null ? null : KnowledgeContentSanitizer.redact(c.title()),
                            c == null ? null : c.category(), c != null);
                }).toList())).toList();
        return new RoadmapResponse(base.id(), base.title(), base.goal(), base.evidenceStatus(), base.createdAt(), base.updatedAt(), result);
    }

    @Transactional
    public RoadmapResponse updateItem(long roadmapId, long itemId, RoadmapItemUpdateRequest request) {
        User student = requireStudent();
        boolean updated = jdbc.update("UPDATE knowledge_roadmap_items SET title=?,description=?,progress_status=? WHERE roadmap_id=? AND id=? AND EXISTS (SELECT 1 FROM knowledge_roadmaps WHERE id=? AND student_user_id=?)",
                request.title().trim(), request.description().trim(), request.status().name(), roadmapId, itemId, roadmapId, student.getId()) > 0;
        if (!updated) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Roadmap item not found");
        jdbc.update("UPDATE knowledge_roadmaps SET updated_at=? WHERE id=? AND student_user_id=?", LocalDateTime.now(), roadmapId, student.getId());
        return get(roadmapId);
    }

    @Transactional
    public void delete(long id) {
        User student = requireStudent();
        if (!exists(id, student.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Roadmap not found");
        jdbc.update("DELETE FROM knowledge_roadmap_item_sources WHERE item_id IN (SELECT id FROM knowledge_roadmap_items WHERE roadmap_id=?)", id);
        jdbc.update("DELETE FROM knowledge_roadmap_items WHERE roadmap_id=?", id);
        jdbc.update("DELETE FROM knowledge_roadmaps WHERE id=? AND student_user_id=?", id, student.getId());
    }

    private void addGeneralItem(long roadmap, int position, String phase, String description) {
        insertId("INSERT INTO knowledge_roadmap_items (roadmap_id,position_index,phase,title,description,source_kind) VALUES (?,?,?,?,?,'GENERAL_GUIDANCE')",
                roadmap, position, phase, phase, description);
    }
    private boolean exists(long id, long owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM knowledge_roadmaps WHERE id=? AND student_user_id=?)", Boolean.class, id, owner));
    }
    private User requireStudent() {
        User user = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(user.getRole()) || !"APPROVED".equalsIgnoreCase(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved student access required");
        return user;
    }
    private static String roadmapTitle(String goal) { return goal.length() > 160 ? goal.substring(0, 157) + "..." : goal; }
    private long insertId(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        Map<String, Object> keys = Objects.requireNonNull(holder.getKeys());
        Object id = keys.entrySet().stream().filter(e -> "id".equalsIgnoreCase(e.getKey())).map(Map.Entry::getValue).findFirst()
                .orElseGet(() -> keys.values().stream().filter(Number.class::isInstance).findFirst().orElseThrow());
        return ((Number) id).longValue();
    }
    private record RoadmapBase(long id, String title, String goal, String evidenceStatus,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {}
    private record ItemBase(long id, int position, String phase, String title, String description,
                            String sourceKind, String status) {}
    private record RoadmapSource(long contributionId, int version) {}
}
