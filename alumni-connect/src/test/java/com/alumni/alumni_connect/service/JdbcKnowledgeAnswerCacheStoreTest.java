package com.alumni.alumni_connect.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JdbcKnowledgeAnswerCacheStoreTest {
    @Test
    void cleanupDatabaseFailureIsContainedAndDoesNotEscapeTheScheduler() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("disposable database unavailable"))
                .when(jdbc).update(org.mockito.ArgumentMatchers.contains(
                        "DELETE FROM knowledge_shared_answer_cache"));
        var store = new JdbcKnowledgeAnswerCacheStore(jdbc, new SimpleMeterRegistry());

        assertDoesNotThrow(store::cleanupExpired);

        org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.contains(
                "DELETE FROM knowledge_shared_answer_cache"));
    }

    @Test
    void storesAndInvalidatesVersionBoundEntriesAndExpiresThem() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_cache_entry;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE knowledge_shared_answer_cache (
                    cache_key CHAR(64) PRIMARY KEY,cache_version SMALLINT,canonical_intent VARCHAR(48),
                    canonical_question VARCHAR(500),answer_text MEDIUMTEXT,evidence_status VARCHAR(32),
                    answer_mode VARCHAR(40),provider_id VARCHAR(48),model_id VARCHAR(100),prompt_version VARCHAR(40),
                    cache_status VARCHAR(16),created_at TIMESTAMP,last_validated_at TIMESTAMP,expires_at TIMESTAMP,
                    hit_count BIGINT DEFAULT 0,last_accessed_at TIMESTAMP,invalidated_at TIMESTAMP,invalidation_reason VARCHAR(32)
                )
                """);
        jdbc.execute("""
                CREATE TABLE knowledge_shared_answer_cache_source (
                    cache_key CHAR(64),contribution_id BIGINT,version_number INT,
                    PRIMARY KEY(cache_key,contribution_id,version_number),
                    FOREIGN KEY(cache_key) REFERENCES knowledge_shared_answer_cache(cache_key) ON DELETE CASCADE
                )
                """);
        jdbc.execute("CREATE TABLE knowledge_shared_answer_cache_lock (cache_key CHAR(64) PRIMARY KEY,lock_token CHAR(36),lease_expires_at TIMESTAMP)");
        var store = new JdbcKnowledgeAnswerCacheStore(jdbc, new SimpleMeterRegistry());
        String key = "b".repeat(64);
        String lock = UUID.randomUUID().toString();
        assertTrue(store.tryAcquire(key, lock, Duration.ofSeconds(60)));
        var entry = new KnowledgeAnswerCacheStore.Entry(key, 1, "INTERVIEW_PREPARATION", "INTERVIEW_PREPARATION",
                "Grounded answer [7:2]", "ALUMNI_REPORTED_EVIDENCE", "OPENAI_GROUNDED", "openai",
                "gpt-4.1-mini", "grounded-answer-v1", List.of(new KnowledgeAnswerCacheStore.SourceRef(7, 2)),
                java.time.LocalDateTime.now().plusDays(7));

        assertTrue(store.save(entry, lock));
        assertEquals(entry.answer(), store.find(key).orElseThrow().answer());
        store.recordHit(key);
        assertEquals(1L, jdbc.queryForObject("SELECT hit_count FROM knowledge_shared_answer_cache WHERE cache_key=?", Long.class, key));
        store.invalidateContribution(7, "consent_revoked");
        assertTrue(store.find(key).isEmpty());
        assertEquals("INVALIDATED", jdbc.queryForObject("SELECT cache_status FROM knowledge_shared_answer_cache WHERE cache_key=?", String.class, key));

        String expiring = "c".repeat(64);
        var expiredEntry = new KnowledgeAnswerCacheStore.Entry(expiring, 1, "INTERVIEW_PREPARATION", "intent",
                "Grounded answer [7:2]", "ALUMNI_REPORTED_EVIDENCE", "OPENAI_GROUNDED", "openai",
                "gpt-4.1-mini", "grounded-answer-v1", List.of(new KnowledgeAnswerCacheStore.SourceRef(7, 2)),
                java.time.LocalDateTime.now().minusSeconds(1));
        String expiringLock = UUID.randomUUID().toString();
        assertTrue(store.tryAcquire(expiring, expiringLock, Duration.ofSeconds(60)));
        assertTrue(store.save(expiredEntry, expiringLock));
        assertTrue(store.find(expiring).isEmpty());
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT cache_status FROM knowledge_shared_answer_cache WHERE cache_key=?", String.class, expiring));
        store.release(key, lock);
        jdbc.update("UPDATE knowledge_shared_answer_cache_lock SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE cache_key=?", expiring);
        String activeKey = "d".repeat(64);
        assertTrue(store.tryAcquire(activeKey, UUID.randomUUID().toString(), Duration.ofSeconds(60)));

        store.cleanupExpired();

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_shared_answer_cache", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_shared_answer_cache_source", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_shared_answer_cache_lock", Integer.class));
        assertEquals(activeKey, jdbc.queryForObject("SELECT cache_key FROM knowledge_shared_answer_cache_lock", String.class));
    }

    @Test
    void mysqlStyleLeaseAllowsOnlyOneConcurrentOwnerAndRecoversAfterExpiry() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_cache_lock;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE knowledge_shared_answer_cache_lock (
                    cache_key CHAR(64) PRIMARY KEY,
                    lock_token CHAR(36) NOT NULL,
                    lease_expires_at TIMESTAMP NOT NULL
                )
                """);
        var store = new JdbcKnowledgeAnswerCacheStore(jdbc, new SimpleMeterRegistry());
        String key = "a".repeat(64);
        int callers = 20;
        var pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger owners = new AtomicInteger();
        List<String> acquiredTokens = new ArrayList<>();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            String token = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                if (store.tryAcquire(key, token, Duration.ofSeconds(60))) {
                    owners.incrementAndGet();
                    synchronized (acquiredTokens) { acquiredTokens.add(token); }
                }
                return null;
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        for (var future : futures) future.get(5, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, owners.get());
        store.release(key, acquiredTokens.getFirst());
        assertTrue(store.tryAcquire(key, UUID.randomUUID().toString(), Duration.ofSeconds(60)));
        jdbc.update("UPDATE knowledge_shared_answer_cache_lock SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE cache_key=?", key);
        assertTrue(store.tryAcquire(key, UUID.randomUUID().toString(), Duration.ofSeconds(60)));
    }

    @Test
    void independentStoreClientsShareEntriesAndFenceStaleLeaseOwners() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:knowledge_cache_two_clients;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE knowledge_shared_answer_cache (cache_key CHAR(64) PRIMARY KEY,cache_version SMALLINT,canonical_intent VARCHAR(48),canonical_question VARCHAR(500),answer_text MEDIUMTEXT,evidence_status VARCHAR(32),answer_mode VARCHAR(40),provider_id VARCHAR(48),model_id VARCHAR(100),prompt_version VARCHAR(40),cache_status VARCHAR(16),created_at TIMESTAMP,last_validated_at TIMESTAMP,expires_at TIMESTAMP,hit_count BIGINT DEFAULT 0,last_accessed_at TIMESTAMP,invalidated_at TIMESTAMP,invalidation_reason VARCHAR(32))");
        jdbc.execute("CREATE TABLE knowledge_shared_answer_cache_source (cache_key CHAR(64),contribution_id BIGINT,version_number INT,PRIMARY KEY(cache_key,contribution_id,version_number),FOREIGN KEY(cache_key) REFERENCES knowledge_shared_answer_cache(cache_key) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE knowledge_shared_answer_cache_lock (cache_key CHAR(64) PRIMARY KEY,lock_token CHAR(36),lease_expires_at TIMESTAMP)");
        var firstInstance = new JdbcKnowledgeAnswerCacheStore(jdbc, new SimpleMeterRegistry());
        var secondInstance = new JdbcKnowledgeAnswerCacheStore(new JdbcTemplate(dataSource), new SimpleMeterRegistry());
        String key = "e".repeat(64);
        String oldOwner = UUID.randomUUID().toString();
        String newOwner = UUID.randomUUID().toString();
        assertTrue(firstInstance.tryAcquire(key, oldOwner, Duration.ofSeconds(60)));
        jdbc.update("UPDATE knowledge_shared_answer_cache_lock SET lease_expires_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP) WHERE cache_key=?", key);
        assertTrue(secondInstance.tryAcquire(key, newOwner, Duration.ofSeconds(60)));

        firstInstance.release(key, oldOwner);
        assertEquals(newOwner, jdbc.queryForObject("SELECT lock_token FROM knowledge_shared_answer_cache_lock WHERE cache_key=?", String.class, key));
        var stale = entry(key);
        assertFalse(firstInstance.save(stale, oldOwner));
        assertTrue(secondInstance.save(entry(key), newOwner));
        assertEquals(stale.answer(), firstInstance.find(key).orElseThrow().answer());
    }

    private static KnowledgeAnswerCacheStore.Entry entry(String key) {
        return new KnowledgeAnswerCacheStore.Entry(key, 1, "INTERVIEW_PREPARATION", "INTERVIEW_PREPARATION",
                "Grounded answer [7:2]", "ALUMNI_REPORTED_EVIDENCE", "OPENAI_GROUNDED", "openai",
                "gpt-4.1-mini", "grounded-answer-v1", List.of(new KnowledgeAnswerCacheStore.SourceRef(7, 2)),
                java.time.LocalDateTime.now().plusDays(1));
    }
}
