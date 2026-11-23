package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.*;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeContributionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeContributionService.class);
    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final UserRepository users;
    private final KnowledgeAnswerCacheStore answerCache;

    public KnowledgeContributionService(JdbcTemplate jdbc, CurrentUserService currentUser, UserRepository users) {
        this(jdbc, currentUser, users, null);
    }

    @Autowired
    public KnowledgeContributionService(JdbcTemplate jdbc, CurrentUserService currentUser, UserRepository users,
                                        KnowledgeAnswerCacheStore answerCache) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.users = users;
        this.answerCache = answerCache;
    }

    @Transactional
    public KnowledgeContributionResponse create(KnowledgeContributionRequest request) {
        User owner = requireEligibleAlumni();
        validateContent(request);
        long id = insertId("INSERT INTO knowledge_contributions (owner_user_id) VALUES (?)", owner.getId());
        long versionId = insertVersion(id, 1, owner.getId(), request, false, false);
        saveEntries(versionId, request.entries());
        audit(id, 1, owner.getId(), "CREATED", null, null);
        return ownResponse(id, owner.getId());
    }

    @Transactional(readOnly = true)
    public KnowledgeContributionResponse getMine(long id) {
        User owner = requireEligibleAlumni();
        return ownResponse(id, owner.getId());
    }

    @Transactional(readOnly = true)
    public List<KnowledgeContributionResponse> listVersions(long id) {
        User owner = requireEligibleAlumni();
        List<Base> rows = jdbc.query("""
                SELECT c.id, c.status, v.version_number AS current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v ON v.contribution_id=c.id
                WHERE c.id=? AND c.owner_user_id=? ORDER BY v.version_number DESC
                """, (rs, n) -> base(rs), id, owner.getId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contribution not found");
        return withEntries(rows);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeContributionResponse> listMine(int page, int size) {
        User owner = requireEligibleAlumni();
        int bounded = boundedSize(size);
        List<Base> rows = jdbc.query("""
                SELECT c.id, c.status, c.current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.current_version
                WHERE c.owner_user_id=? ORDER BY c.updated_at DESC, c.id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> base(rs), owner.getId(), bounded + 1, (long) Math.max(0, page) * bounded);
        if (rows.size() > bounded) rows.remove(rows.size() - 1);
        return withEntries(rows);
    }

    @Transactional
    public KnowledgeContributionResponse update(long id, KnowledgeContributionRequest request) {
        User owner = requireEligibleAlumni();
        validateContent(request);
        Base current = ownedBase(id, owner.getId());
        if ("WITHDRAWN".equals(current.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Withdrawn contributions cannot be edited");
        int next = current.version() + 1;
        long versionId = insertVersion(id, next, owner.getId(), request, false, false);
        saveEntries(versionId, request.entries());
        jdbc.update("UPDATE knowledge_contributions SET current_version=?, updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND owner_user_id=?",
                next, id, owner.getId());
        audit(id, next, owner.getId(), "VERSION_CREATED", false, false);
        return ownResponse(id, owner.getId());
    }

    @Transactional
    public KnowledgeContributionResponse publish(long id, PublishConsentRequest request) {
        User owner = requireEligibleAlumni();
        if (!request.studentVisibilityConsent() || !request.aiRetrievalConsent())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both student visibility and AI retrieval consent are required to publish");
        Base base = ownedBase(id, owner.getId());
        if ("WITHDRAWN".equals(base.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Withdrawn contributions cannot be published");
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE knowledge_contribution_versions SET student_visibility_consent=TRUE, ai_retrieval_consent=TRUE, student_consent_at=?, ai_consent_at=? WHERE id=?",
                now, now, base.versionId());
        int freshnessDays = jdbc.queryForObject("SELECT freshness_interval_days FROM knowledge_contributions WHERE id=?", Integer.class, id);
        jdbc.update("UPDATE knowledge_contributions SET status='PUBLISHED', published_version=current_version, published_at=?, withdrawn_at=NULL, updated_at=?, last_confirmed_at=?, reminder_due_at=?, last_reminder_sent_at=NULL WHERE id=? AND owner_user_id=?",
                now, now, now, now.plusDays(freshnessDays), id, owner.getId());
        invalidateCachedAnswers(id, "contribution_republished");
        audit(id, base.version(), owner.getId(), "PUBLISHED", true, true);
        return ownResponse(id, owner.getId());
    }

    @Transactional
    public KnowledgeContributionResponse withdraw(long id) {
        User owner = requireEligibleAlumni();
        Base base = ownedBase(id, owner.getId());
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE knowledge_contributions SET status='WITHDRAWN', withdrawn_at=?, updated_at=? WHERE id=? AND owner_user_id=?",
                now, now, id, owner.getId());
        invalidateCachedAnswers(id, "contribution_withdrawn");
        audit(id, base.version(), owner.getId(), "WITHDRAWN", null, null);
        return ownResponse(id, owner.getId());
    }

    @Transactional
    public KnowledgeContributionResponse updateConsent(long id, ConsentUpdateRequest request) {
        User owner = requireEligibleAlumni();
        Base base = ownedBase(id, owner.getId());
        boolean student = request.studentVisibilityConsent();
        boolean ai = request.aiRetrievalConsent();
        boolean revoking = !student || !ai;
        LocalDateTime now = LocalDateTime.now();
        Long targetId = base.versionId();
        jdbc.update("UPDATE knowledge_contribution_versions SET student_visibility_consent=?, ai_retrieval_consent=?, student_consent_at=?, ai_consent_at=? WHERE id=?",
                student, ai, student ? now : null, ai ? now : null, targetId);
        // Grants apply only to the current immutable version. A revocation also
        // suppresses that scope on the active published snapshot immediately.
        // The AND preserves any prior revocation; a newer draft cannot re-grant it.
        if (base.publishedVersion() != null && base.publishedVersion() != base.version()) {
            Boolean[] previouslyPublishedConsent = jdbc.query("""
                    SELECT student_visibility_consent, ai_retrieval_consent
                    FROM knowledge_contribution_versions
                    WHERE contribution_id=? AND version_number=?
                    """, rs -> rs.next()
                    ? new Boolean[]{rs.getBoolean(1), rs.getBoolean(2)} : null,
                    id, base.publishedVersion());
            if (previouslyPublishedConsent != null) {
                boolean publishedStudent = previouslyPublishedConsent[0] && student;
                boolean publishedAi = previouslyPublishedConsent[1] && ai;
                boolean revoked = previouslyPublishedConsent[0] && !student
                        || previouslyPublishedConsent[1] && !ai;
                if (revoked) {
                    jdbc.update("""
                            UPDATE knowledge_contribution_versions
                            SET student_visibility_consent=?, ai_retrieval_consent=?,
                                student_consent_at=CASE WHEN ? THEN student_consent_at ELSE NULL END,
                                ai_consent_at=CASE WHEN ? THEN ai_consent_at ELSE NULL END
                            WHERE contribution_id=? AND version_number=?
                            """, publishedStudent, publishedAi, publishedStudent, publishedAi,
                            id, base.publishedVersion());
                    audit(id, base.publishedVersion(), owner.getId(), "CONSENT_REVOKED",
                            publishedStudent, publishedAi);
                }
            }
        }
        if (revoking) invalidateCachedAnswers(id, "consent_revoked");
        audit(id, base.version(), owner.getId(), "CONSENT_CHANGED", student, ai);
        return ownResponse(id, owner.getId());
    }

    @Transactional(readOnly = true)
    public List<KnowledgeContributionResponse> listStudentVisible(KnowledgeContributionRequest.Category category,
                                                                    int page, int size) {
        User student = currentUser.requireUser();
        if (!"STUDENT".equalsIgnoreCase(student.getRole()) || !"APPROVED".equalsIgnoreCase(student.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved students only");
        int bounded = boundedSize(size);
        String sql = """
                SELECT c.id, c.status, c.published_version AS current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.published_version
                JOIN users u ON u.id=c.owner_user_id
                WHERE c.status='PUBLISHED' AND v.student_visibility_consent=TRUE
                  AND u.role='ALUMNI' AND u.status='APPROVED'
                """ + (category == null ? "" : " AND v.category=?")
                + " ORDER BY c.published_at DESC, c.id DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>();
        if (category != null) args.add(category.name());
        args.add(bounded + 1);
        args.add((long) Math.max(0, page) * bounded);
        List<Base> rows = jdbc.query(sql, (rs, n) -> base(rs), args.toArray());
        if (rows.size() > bounded) rows.remove(rows.size() - 1);
        return withEntries(rows).stream().map(KnowledgeContentSanitizer::publicView).toList();
    }

    /** Current AI retrieval boundary: consent and owner eligibility are rechecked in this query. */
    @Transactional(readOnly = true)
    public List<KnowledgeContributionResponse> listAiEligible(int page, int size) {
        int bounded = boundedSize(size);
        List<Base> rows = jdbc.query("""
                SELECT c.id, c.status, c.published_version AS current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.published_version
                JOIN users u ON u.id=c.owner_user_id
                WHERE c.status='PUBLISHED' AND v.student_visibility_consent=TRUE AND v.ai_retrieval_consent=TRUE
                  AND u.role='ALUMNI' AND u.status='APPROVED'
                ORDER BY c.published_at DESC, c.id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> base(rs), bounded + 1, (long) Math.max(0, page) * bounded);
        if (rows.size() > bounded) rows.remove(rows.size() - 1);
        return withEntries(rows);
    }

    /** Revalidates exact source versions immediately before retrieval content is served. */
    @Transactional(readOnly = true)
    public List<KnowledgeContributionResponse> recheckAiEligible(List<ContributionVersionRef> references) {
        if (references == null || references.isEmpty()) return List.of();
        List<ContributionVersionRef> bounded = references.stream().distinct().limit(250).toList();
        String predicates = String.join(" OR ", Collections.nCopies(bounded.size(),
                "(c.id=? AND c.published_version=? AND v.version_number=c.published_version)"));
        String sql = """
                SELECT c.id, c.status, c.published_version AS current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.published_version
                JOIN users u ON u.id=c.owner_user_id
                WHERE c.status='PUBLISHED' AND v.student_visibility_consent=TRUE AND v.ai_retrieval_consent=TRUE
                  AND u.role='ALUMNI' AND u.status='APPROVED' AND (
                """ + predicates + ")";
        List<Object> args = new ArrayList<>();
        for (ContributionVersionRef ref : bounded) {
            args.add(ref.contributionId());
            args.add(ref.version());
        }
        List<Base> rows = jdbc.query(sql, (rs, n) -> base(rs), args.toArray());
        return withEntries(rows);
    }

    /** Cache hits require both current consent/version eligibility and a fresh author confirmation. */
    @Transactional(readOnly = true)
    public Set<String> recheckFreshAiEligibleKeys(List<ContributionVersionRef> references) {
        if (references == null || references.isEmpty()) return Set.of();
        List<ContributionVersionRef> bounded = references.stream().distinct().limit(5).toList();
        String predicates = String.join(" OR ", Collections.nCopies(bounded.size(),
                "(c.id=? AND c.published_version=? AND v.version_number=c.published_version)"));
        String sql = """
                SELECT c.id,c.published_version
                FROM knowledge_contributions c
                JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.published_version
                JOIN users u ON u.id=c.owner_user_id
                WHERE c.status='PUBLISHED' AND v.student_visibility_consent=TRUE AND v.ai_retrieval_consent=TRUE
                  AND u.role='ALUMNI' AND u.status='APPROVED'
                  AND c.last_confirmed_at IS NOT NULL
                  AND DATE_ADD(c.last_confirmed_at, INTERVAL c.freshness_interval_days DAY)>CURRENT_TIMESTAMP(6)
                  AND (
                """ + predicates + ")";
        List<Object> args = new ArrayList<>();
        for (ContributionVersionRef ref : bounded) {
            args.add(ref.contributionId());
            args.add(ref.version());
        }
        return jdbc.query(sql, (rs, n) -> rs.getLong(1) + ":" + rs.getInt(2), args.toArray())
                .stream().collect(Collectors.toUnmodifiableSet());
    }

    public record ContributionVersionRef(long contributionId, int version) {}

    private User requireEligibleAlumni() {
        User user = currentUser.requireUser();
        if (!"ALUMNI".equalsIgnoreCase(user.getRole()) || !"APPROVED".equalsIgnoreCase(user.getStatus()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved alumni only");
        return user;
    }

    private void invalidateCachedAnswers(long contributionId, String reason) {
        if (answerCache == null) return;
        try {
            answerCache.invalidateContribution(contributionId, reason);
        } catch (RuntimeException failure) {
            // Reads independently recheck consent/version/freshness. Cache invalidation is an optimization.
            log.warn("Shared knowledge answer cache invalidation failed (errorType={})",
                    failure.getClass().getSimpleName());
        }
    }

    private void validateContent(KnowledgeContributionRequest r) {
        if (r.entries() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Entries are required");
        if (r.entries().stream().anyMatch(entry -> entry == null || entry.type() == null))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every entry requires a type");
        Map<KnowledgeContributionRequest.EntryType, Long> counts = r.entries().stream()
                .collect(Collectors.groupingBy(KnowledgeContributionRequest.Entry::type, Collectors.counting()));
        Map<KnowledgeContributionRequest.EntryType, Integer> limits = Map.of(
                KnowledgeContributionRequest.EntryType.TECHNOLOGY, 20,
                KnowledgeContributionRequest.EntryType.INTERVIEW_ROUND, 8,
                KnowledgeContributionRequest.EntryType.INTERVIEW_TOPIC, 40,
                KnowledgeContributionRequest.EntryType.INTERVIEW_QUESTION, 40,
                KnowledgeContributionRequest.EntryType.RESOURCE, 10,
                KnowledgeContributionRequest.EntryType.CHALLENGE, 10,
                KnowledgeContributionRequest.EntryType.LESSON, 10,
                KnowledgeContributionRequest.EntryType.ROLE_TRANSITION, 10);
        for (var entry : r.entries()) {
            if (entry == null || entry.type() == null || entry.position() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every entry requires a type and position");
            if (entry.url() != null && !entry.url().isBlank() && !entry.url().matches("(?i)^https://[^\\s]+$"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource URLs must use HTTPS");
            if (entry.value() == null && entry.fromRole() == null && entry.toRole() == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each entry requires a value or role transition");
        }
        counts.forEach((type, count) -> {
            if (count > limits.get(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many entries of type " + type);
        });
    }

    private long insertVersion(long contributionId, int version, long ownerId,
                               KnowledgeContributionRequest request, boolean student, boolean ai) {
        return insertId("""
                INSERT INTO knowledge_contribution_versions
                  (contribution_id, version_number, category, title, narrative, company_name, job_role,
                   experience_year, student_visibility_consent, ai_retrieval_consent, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, contributionId, version, request.category().name(), request.title().trim(), request.narrative().trim(),
                blankToNull(request.companyName()), blankToNull(request.jobRole()), request.experienceYear(), student, ai, ownerId);
    }

    private void saveEntries(long versionId, List<KnowledgeContributionRequest.Entry> entries) {
        for (var e : entries) jdbc.update("""
                INSERT INTO knowledge_contribution_entries
                  (version_id, entry_type, position_index, parent_position, value_text, detail_text, url, from_role, to_role, entry_year)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, versionId, e.type().name(), e.position(), e.parentPosition(), blankToNull(e.value()),
                blankToNull(e.detail()), blankToNull(e.url()), blankToNull(e.fromRole()), blankToNull(e.toRole()), e.year());
    }

    private void audit(long id, int version, long actor, String type, Boolean student, Boolean ai) {
        jdbc.update("INSERT INTO knowledge_contribution_audit (contribution_id, version_number, actor_user_id, event_type, student_visibility_consent, ai_retrieval_consent) VALUES (?, ?, ?, ?, ?, ?)",
                id, version, actor, type, student, ai);
    }

    private long insertId(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update((PreparedStatementCreator) connection -> {
            var ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, key);
        Map<String, Object> generated = Objects.requireNonNull(key.getKeys());
        Object generatedId = generated.entrySet().stream()
                .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue).findFirst()
                .orElseGet(() -> generated.values().stream().filter(Number.class::isInstance).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Database did not return a generated ID")));
        return ((Number) generatedId).longValue();
    }

    private KnowledgeContributionResponse ownResponse(long id, long owner) {
        List<Base> rows = jdbc.query("""
                SELECT c.id, c.status, c.current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.current_version
                WHERE c.id=? AND c.owner_user_id=?
                """, (rs, n) -> base(rs), id, owner);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contribution not found");
        return withEntries(rows).getFirst();
    }

    private Base ownedBase(long id, long owner) {
        return jdbc.query("""
                SELECT c.id, c.status, c.current_version, c.published_version, c.created_at, c.updated_at,
                       c.published_at, v.id AS version_id, v.category, v.title, v.narrative, v.company_name,
                       v.job_role, v.experience_year, v.student_visibility_consent, v.ai_retrieval_consent
                FROM knowledge_contributions c JOIN knowledge_contribution_versions v
                  ON v.contribution_id=c.id AND v.version_number=c.current_version
                WHERE c.id=? AND c.owner_user_id=?
                """, (rs, n) -> base(rs), id, owner).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contribution not found"));
    }

    private Base base(java.sql.ResultSet rs) throws java.sql.SQLException {
        int year = rs.getInt("experience_year"); Integer experienceYear = rs.wasNull() ? null : year;
        int published = rs.getInt("published_version"); Integer publishedVersion = rs.wasNull() ? null : published;
        return new Base(rs.getLong("id"), rs.getString("status"), rs.getInt("current_version"), publishedVersion,
                rs.getLong("version_id"), rs.getString("category"), rs.getString("title"), rs.getString("narrative"),
                rs.getString("company_name"), rs.getString("job_role"), experienceYear,
                rs.getBoolean("student_visibility_consent"), rs.getBoolean("ai_retrieval_consent"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime());
    }

    private List<KnowledgeContributionResponse> withEntries(List<Base> rows) {
        if (rows.isEmpty()) return List.of();
        String marks = String.join(",", Collections.nCopies(rows.size(), "?"));
        List<Object> ids = rows.stream().map(Base::versionId).map(x -> (Object) x).toList();
        Map<Long, List<KnowledgeContributionResponse.Entry>> entries = new HashMap<>();
        jdbc.query("SELECT * FROM knowledge_contribution_entries WHERE version_id IN (" + marks + ") ORDER BY version_id, entry_type, position_index",
                rs -> {
                    int parent = rs.getInt("parent_position"); Integer parentPosition = rs.wasNull() ? null : parent;
                    int year = rs.getInt("entry_year"); Integer entryYear = rs.wasNull() ? null : year;
                    entries.computeIfAbsent(rs.getLong("version_id"), ignored -> new ArrayList<>()).add(
                            new KnowledgeContributionResponse.Entry(rs.getString("entry_type"), rs.getInt("position_index"),
                                    parentPosition, rs.getString("value_text"), rs.getString("detail_text"), rs.getString("url"),
                                    rs.getString("from_role"), rs.getString("to_role"), entryYear));
                }, ids.toArray());
        return rows.stream().map(b -> new KnowledgeContributionResponse(b.id(), b.version(), b.publishedVersion(), b.category(), b.title(), b.narrative(),
                b.company(), b.role(), b.year(), b.status(), b.studentConsent(), b.aiConsent(), b.createdAt(), b.updatedAt(),
                b.publishedAt(), entries.getOrDefault(b.versionId(), List.of()))).toList();
    }

    private static int boundedSize(int size) { return Math.max(1, Math.min(size, 50)); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private record Base(long id, String status, int version, Integer publishedVersion, long versionId,
                        String category, String title, String narrative, String company, String role, Integer year,
                        boolean studentConsent, boolean aiConsent, LocalDateTime createdAt,
                        LocalDateTime updatedAt, LocalDateTime publishedAt) {}
}
