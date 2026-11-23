package com.alumni.alumni_connect.service;

import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcKnowledgeAnswerCacheStore implements KnowledgeAnswerCacheStore {
    private static final Logger log = LoggerFactory.getLogger(JdbcKnowledgeAnswerCacheStore.class);
    private static final int CLEANUP_BATCH_SIZE = 500;
    private static final int MAX_CLEANUP_BATCHES_PER_RUN = 10;
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    public JdbcKnowledgeAnswerCacheStore(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** Bounded multi-instance-safe cleanup; expired entries and leases are never eligible for use. */
    @Scheduled(fixedDelayString = "${app.knowledge.cache.cleanup-delay-ms:3600000}")
    public void cleanupExpired() {
        try {
            int entriesRemoved = deleteInBatches("""
                    DELETE FROM knowledge_shared_answer_cache
                    WHERE cache_status IN ('INVALIDATED','EXPIRED') OR expires_at<=CURRENT_TIMESTAMP(6)
                    """);
            int leasesRemoved = deleteInBatches("""
                    DELETE FROM knowledge_shared_answer_cache_lock
                    WHERE lease_expires_at<=CURRENT_TIMESTAMP(6)
                    """);
            if (entriesRemoved > 0) meters.counter("mentorax.knowledge.answer.cache.cleanup", "kind", "entries")
                    .increment(entriesRemoved);
            if (leasesRemoved > 0) meters.counter("mentorax.knowledge.answer.cache.cleanup", "kind", "leases")
                    .increment(leasesRemoved);
        } catch (RuntimeException failure) {
            log.warn("Shared knowledge answer cache cleanup failed (errorType={})",
                    failure.getClass().getSimpleName());
        }
    }

    private int deleteInBatches(String sql) {
        int removed = 0;
        for (int batch = 0; batch < MAX_CLEANUP_BATCHES_PER_RUN; batch++) {
            int count = jdbc.update(sql + " LIMIT " + CLEANUP_BATCH_SIZE);
            removed += count;
            if (count < CLEANUP_BATCH_SIZE) break;
        }
        return removed;
    }

    @Override
    public Optional<Entry> find(String cacheKey) {
        int expired = jdbc.update("""
                UPDATE knowledge_shared_answer_cache SET cache_status='EXPIRED'
                WHERE cache_key=? AND cache_status='ACTIVE' AND expires_at<=CURRENT_TIMESTAMP(6)
                """, cacheKey);
        if (expired > 0) meters.counter("mentorax.knowledge.answer.cache.expired", "outcome", "marked")
                .increment(expired);
        List<EntryBase> rows = jdbc.query("""
                SELECT cache_key,cache_version,canonical_intent,canonical_question,answer_text,evidence_status,
                       answer_mode,provider_id,model_id,prompt_version,expires_at
                FROM knowledge_shared_answer_cache
                WHERE cache_key=? AND cache_status='ACTIVE' AND expires_at>CURRENT_TIMESTAMP(6)
                """, (rs, n) -> new EntryBase(rs.getString("cache_key"), rs.getInt("cache_version"),
                rs.getString("canonical_intent"), rs.getString("canonical_question"), rs.getString("answer_text"),
                rs.getString("evidence_status"), rs.getString("answer_mode"), rs.getString("provider_id"),
                rs.getString("model_id"), rs.getString("prompt_version"),
                rs.getTimestamp("expires_at").toLocalDateTime()), cacheKey);
        if (rows.isEmpty()) return Optional.empty();
        List<SourceRef> sources = jdbc.query("""
                SELECT contribution_id,version_number FROM knowledge_shared_answer_cache_source
                WHERE cache_key=? ORDER BY contribution_id,version_number
                """, (rs, n) -> new SourceRef(rs.getLong(1), rs.getInt(2)), cacheKey);
        if (sources.isEmpty()) {
            invalidate(cacheKey, "malformed_entry");
            return Optional.empty();
        }
        EntryBase base = rows.getFirst();
        return Optional.of(new Entry(base.cacheKey(), base.cacheVersion(), base.intent(), base.canonicalQuestion(),
                base.answer(), base.evidenceStatus(), base.answerMode(), base.providerId(), base.modelId(),
                base.promptVersion(), List.copyOf(sources), base.expiresAt()));
    }

    @Override
    public void recordHit(String cacheKey) {
        jdbc.update("""
                UPDATE knowledge_shared_answer_cache
                SET hit_count=hit_count+1,last_accessed_at=CURRENT_TIMESTAMP(6),
                    last_validated_at=CURRENT_TIMESTAMP(6)
                WHERE cache_key=? AND cache_status='ACTIVE' AND expires_at>CURRENT_TIMESTAMP(6)
                """, cacheKey);
    }

    @Override
    public boolean tryAcquire(String cacheKey, String lockToken, Duration lease) {
        long seconds = Math.max(5, Math.min(120, lease.toSeconds()));
        int inserted = jdbc.update("""
                INSERT IGNORE INTO knowledge_shared_answer_cache_lock(cache_key,lock_token,lease_expires_at)
                VALUES(?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)))
                """, cacheKey, lockToken, seconds);
        if (inserted == 1) return true;
        return jdbc.update("""
                UPDATE knowledge_shared_answer_cache_lock
                SET lock_token=?,lease_expires_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6))
                WHERE cache_key=? AND lease_expires_at<=CURRENT_TIMESTAMP(6)
                """, lockToken, seconds, cacheKey) == 1;
    }

    @Override
    public void release(String cacheKey, String lockToken) {
        jdbc.update("DELETE FROM knowledge_shared_answer_cache_lock WHERE cache_key=? AND lock_token=?",
                cacheKey, lockToken);
    }

    @Override
    @Transactional
    public boolean save(Entry entry, String lockToken) {
        List<String> validLease = jdbc.query("""
                SELECT cache_key FROM knowledge_shared_answer_cache_lock
                WHERE cache_key=? AND lock_token=? AND lease_expires_at>CURRENT_TIMESTAMP(6) FOR UPDATE
                """, (rs, n) -> rs.getString(1), entry.cacheKey(), lockToken);
        if (validLease.isEmpty()) return false;
        jdbc.update("""
                INSERT INTO knowledge_shared_answer_cache(
                    cache_key,cache_version,canonical_intent,canonical_question,answer_text,evidence_status,
                    answer_mode,provider_id,model_id,prompt_version,cache_status,created_at,last_validated_at,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),?)
                ON DUPLICATE KEY UPDATE cache_version=VALUES(cache_version),canonical_intent=VALUES(canonical_intent),
                    canonical_question=VALUES(canonical_question),answer_text=VALUES(answer_text),
                    evidence_status=VALUES(evidence_status),answer_mode=VALUES(answer_mode),
                    provider_id=VALUES(provider_id),model_id=VALUES(model_id),prompt_version=VALUES(prompt_version),
                    cache_status='ACTIVE',created_at=CURRENT_TIMESTAMP(6),last_validated_at=CURRENT_TIMESTAMP(6),
                    expires_at=VALUES(expires_at),invalidated_at=NULL,invalidation_reason=NULL,hit_count=0,last_accessed_at=NULL
                """, entry.cacheKey(), entry.cacheVersion(), entry.intent(), entry.canonicalQuestion(),
                entry.answer(), entry.evidenceStatus(), entry.answerMode(), entry.providerId(), entry.modelId(),
                entry.promptVersion(), Timestamp.valueOf(entry.expiresAt()));
        jdbc.update("DELETE FROM knowledge_shared_answer_cache_source WHERE cache_key=?", entry.cacheKey());
        List<Object[]> refs = entry.sources().stream().map(source -> new Object[]{
                entry.cacheKey(), source.contributionId(), source.version()}).toList();
        jdbc.batchUpdate("""
                INSERT INTO knowledge_shared_answer_cache_source(cache_key,contribution_id,version_number)
                VALUES(?,?,?)
                """, refs);
        return true;
    }

    @Override
    public void invalidate(String cacheKey, String reason) {
        String safeReason = switch (reason) {
            case "source_ineligible", "source_version_mismatch", "malformed_entry", "source_not_fresh" -> reason;
            default -> "validation_failed";
        };
        int invalidated = jdbc.update("""
                UPDATE knowledge_shared_answer_cache SET cache_status='INVALIDATED',
                    invalidated_at=CURRENT_TIMESTAMP(6),invalidation_reason=?
                WHERE cache_key=? AND cache_status='ACTIVE'
                """, safeReason, cacheKey);
        if (invalidated > 0) meters.counter("mentorax.knowledge.answer.cache.invalidated", "reason", safeReason)
                .increment(invalidated);
    }

    @Override
    public void invalidateContribution(long contributionId, String reason) {
        String safeReason = switch (reason) {
            case "contribution_withdrawn", "consent_revoked", "contribution_republished" -> reason;
            default -> "source_ineligible";
        };
        int invalidated = jdbc.update("""
                UPDATE knowledge_shared_answer_cache SET cache_status='INVALIDATED',
                    invalidated_at=CURRENT_TIMESTAMP(6),invalidation_reason=?
                WHERE cache_status='ACTIVE' AND cache_key IN (
                    SELECT cache_key FROM knowledge_shared_answer_cache_source WHERE contribution_id=?
                )
                """, safeReason, contributionId);
        if (invalidated > 0) meters.counter("mentorax.knowledge.answer.cache.invalidated", "reason", safeReason)
                .increment(invalidated);
    }

    private record EntryBase(String cacheKey, int cacheVersion, String intent, String canonicalQuestion,
                             String answer, String evidenceStatus, String answerMode, String providerId,
                             String modelId, String promptVersion, LocalDateTime expiresAt) {}
}
