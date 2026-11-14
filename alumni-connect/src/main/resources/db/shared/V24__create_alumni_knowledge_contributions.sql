CREATE TABLE knowledge_contributions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_version INT NOT NULL DEFAULT 1,
    published_version INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6) NULL,
    withdrawn_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_contributions_owner FOREIGN KEY (owner_user_id) REFERENCES users(id),
    INDEX idx_knowledge_contributions_owner_updated (owner_user_id, updated_at),
    INDEX idx_knowledge_contributions_public (status, published_version, id)
);

CREATE TABLE knowledge_contribution_versions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contribution_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    category VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    narrative VARCHAR(6000) NOT NULL,
    company_name VARCHAR(150) NULL,
    job_role VARCHAR(150) NULL,
    experience_year SMALLINT NULL,
    student_visibility_consent BOOLEAN NOT NULL DEFAULT FALSE,
    ai_retrieval_consent BOOLEAN NOT NULL DEFAULT FALSE,
    student_consent_at DATETIME(6) NULL,
    ai_consent_at DATETIME(6) NULL,
    created_by_user_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_versions_contribution FOREIGN KEY (contribution_id) REFERENCES knowledge_contributions(id),
    CONSTRAINT fk_knowledge_versions_creator FOREIGN KEY (created_by_user_id) REFERENCES users(id),
    CONSTRAINT uk_knowledge_version_number UNIQUE (contribution_id, version_number),
    INDEX idx_knowledge_versions_consent (contribution_id, version_number, student_visibility_consent, ai_retrieval_consent)
);

CREATE TABLE knowledge_contribution_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version_id BIGINT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    position_index SMALLINT NOT NULL,
    parent_position SMALLINT NULL,
    value_text VARCHAR(1000) NULL,
    detail_text VARCHAR(2000) NULL,
    url VARCHAR(500) NULL,
    from_role VARCHAR(150) NULL,
    to_role VARCHAR(150) NULL,
    entry_year SMALLINT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_entries_version FOREIGN KEY (version_id) REFERENCES knowledge_contribution_versions(id),
    INDEX idx_knowledge_entries_version_position (version_id, entry_type, position_index)
);

CREATE TABLE knowledge_contribution_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    contribution_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    student_visibility_consent BOOLEAN NULL,
    ai_retrieval_consent BOOLEAN NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_audit_contribution FOREIGN KEY (contribution_id) REFERENCES knowledge_contributions(id),
    CONSTRAINT fk_knowledge_audit_version FOREIGN KEY (contribution_id, version_number)
        REFERENCES knowledge_contribution_versions(contribution_id, version_number),
    CONSTRAINT fk_knowledge_audit_actor FOREIGN KEY (actor_user_id) REFERENCES users(id),
    INDEX idx_knowledge_audit_contribution_created (contribution_id, created_at)
);
