CREATE TABLE experience_claims (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    company VARCHAR(150) NOT NULL,
    job_title VARCHAR(150) NOT NULL,
    start_month VARCHAR(32) NULL,
    end_month VARCHAR(32) NULL,
    summary VARCHAR(2000) NOT NULL,
    evidence_summary VARCHAR(2000) NULL,
    evidence_hash VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ai_status VARCHAR(32) NULL DEFAULT 'PENDING',
    ai_findings TEXT NULL,
    appeal_note TEXT NULL,
    reviewer_email VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_claims_user
        FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_experience_claims_user_created
    ON experience_claims (user_id, created_at DESC);

CREATE INDEX idx_experience_claims_status_created
    ON experience_claims (status, created_at DESC);
