ALTER TABLE experience_claims ADD COLUMN request_id VARCHAR(36) NULL;
ALTER TABLE experience_claims ADD COLUMN evidence_storage_key VARCHAR(64) NULL;
ALTER TABLE experience_claims ADD COLUMN encrypted_evidence MEDIUMBLOB NULL;
ALTER TABLE experience_claims ADD COLUMN evidence_file_name VARCHAR(255) NULL;
ALTER TABLE experience_claims ADD COLUMN evidence_media_type VARCHAR(100) NULL;
ALTER TABLE experience_claims ADD COLUMN evidence_size BIGINT NULL;
ALTER TABLE experience_claims ADD COLUMN evidence_uploaded_at DATETIME NULL;

CREATE UNIQUE INDEX uk_experience_claim_user_request
    ON experience_claims (user_id, request_id);

CREATE TABLE experience_claim_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    old_status VARCHAR(32) NULL,
    new_status VARCHAR(32) NULL,
    event_note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_experience_claim_audit_claim_created
    ON experience_claim_audit (claim_id, created_at);
