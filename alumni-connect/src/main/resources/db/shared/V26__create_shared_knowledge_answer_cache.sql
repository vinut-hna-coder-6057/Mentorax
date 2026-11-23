CREATE TABLE knowledge_shared_answer_cache (
    cache_key CHAR(64) NOT NULL,
    cache_version SMALLINT NOT NULL,
    canonical_intent VARCHAR(48) NOT NULL,
    canonical_question VARCHAR(500) NOT NULL,
    answer_text MEDIUMTEXT NOT NULL,
    evidence_status VARCHAR(32) NOT NULL,
    answer_mode VARCHAR(40) NOT NULL,
    provider_id VARCHAR(48) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(40) NOT NULL,
    cache_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_validated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    hit_count BIGINT NOT NULL DEFAULT 0,
    last_accessed_at DATETIME(6) NULL,
    invalidated_at DATETIME(6) NULL,
    invalidation_reason VARCHAR(32) NULL,
    PRIMARY KEY (cache_key),
    CONSTRAINT chk_knowledge_shared_answer_cache_status
        CHECK (cache_status IN ('ACTIVE', 'INVALIDATED', 'EXPIRED')),
    INDEX idx_knowledge_shared_answer_cache_expiry (cache_status, expires_at),
    INDEX idx_knowledge_shared_answer_cache_intent (canonical_intent, cache_status, expires_at)
);

CREATE TABLE knowledge_shared_answer_cache_source (
    cache_key CHAR(64) NOT NULL,
    contribution_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    PRIMARY KEY (cache_key, contribution_id, version_number),
    CONSTRAINT fk_knowledge_answer_cache_source_cache FOREIGN KEY (cache_key)
        REFERENCES knowledge_shared_answer_cache(cache_key) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_answer_cache_source_version FOREIGN KEY (contribution_id, version_number)
        REFERENCES knowledge_contribution_versions(contribution_id, version_number),
    INDEX idx_knowledge_answer_cache_source_version (contribution_id, version_number)
);

CREATE TABLE knowledge_shared_answer_cache_lock (
    cache_key CHAR(64) NOT NULL,
    lock_token CHAR(36) NOT NULL,
    lease_expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (cache_key),
    INDEX idx_knowledge_answer_cache_lock_expiry (lease_expires_at)
);
