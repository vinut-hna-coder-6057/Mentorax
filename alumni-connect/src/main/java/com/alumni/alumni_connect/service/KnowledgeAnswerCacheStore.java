package com.alumni.alumni_connect.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Shared MySQL cache and bounded distributed generation lease. */
public interface KnowledgeAnswerCacheStore {
    Optional<Entry> find(String cacheKey);
    void recordHit(String cacheKey);
    boolean tryAcquire(String cacheKey, String lockToken, Duration lease);
    void release(String cacheKey, String lockToken);
    boolean save(Entry entry, String lockToken);
    void invalidate(String cacheKey, String reason);
    void invalidateContribution(long contributionId, String reason);
    void cleanupExpired();

    record SourceRef(long contributionId, int version) {}
    record Entry(String cacheKey, int cacheVersion, String intent, String canonicalQuestion,
                 String answer, String evidenceStatus, String answerMode, String providerId,
                 String modelId, String promptVersion, List<SourceRef> sources,
                 LocalDateTime expiresAt) {}
}
