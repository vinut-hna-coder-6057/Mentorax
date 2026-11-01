ALTER TABLE experience_claims
    ADD COLUMN evidence_version INT NOT NULL DEFAULT 0;

ALTER TABLE experience_claims
    ADD COLUMN decision_explanation TEXT NULL;

ALTER TABLE experience_claims
    ADD COLUMN requested_evidence TEXT NULL;

ALTER TABLE experience_claims
    ADD COLUMN decision_at DATETIME NULL;

ALTER TABLE experience_claims
    ADD COLUMN corroboration_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CHECKED';

CREATE TABLE experience_claim_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    evidence_version INT NOT NULL,
    evidence_storage_key VARCHAR(64) NOT NULL,
    encrypted_evidence MEDIUMBLOB NOT NULL,
    evidence_file_name VARCHAR(255) NOT NULL,
    evidence_media_type VARCHAR(100) NOT NULL,
    evidence_size BIGINT NOT NULL,
    evidence_hash VARCHAR(128) NOT NULL,
    analysis_status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    analysis_findings TEXT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_claim_evidence_claim
        FOREIGN KEY (claim_id) REFERENCES experience_claims(id) ON DELETE CASCADE,
    CONSTRAINT uk_experience_claim_evidence_version UNIQUE (claim_id, evidence_version),
    CONSTRAINT uk_experience_claim_evidence_hash UNIQUE (claim_id, evidence_hash)
);

CREATE TABLE experience_claim_processing (
    claim_id BIGINT NOT NULL,
    evidence_version INT NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    lease_until DATETIME NULL,
    last_error VARCHAR(500) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (claim_id),
    CONSTRAINT fk_experience_claim_processing_claim
        FOREIGN KEY (claim_id) REFERENCES experience_claims(id) ON DELETE CASCADE
);

UPDATE experience_claims
SET evidence_version = 1
WHERE encrypted_evidence IS NOT NULL AND evidence_version = 0;

INSERT INTO experience_claim_evidence (
    claim_id, evidence_version, evidence_storage_key, encrypted_evidence,
    evidence_file_name, evidence_media_type, evidence_size, evidence_hash,
    analysis_status, analysis_findings, created_at
)
SELECT id, evidence_version, evidence_storage_key, encrypted_evidence,
       evidence_file_name, evidence_media_type, evidence_size, evidence_hash,
       COALESCE(NULLIF(ai_status, ''), 'QUEUED'), ai_findings,
       COALESCE(evidence_uploaded_at, created_at)
FROM experience_claims
WHERE encrypted_evidence IS NOT NULL
  AND evidence_storage_key IS NOT NULL
  AND evidence_file_name IS NOT NULL
  AND evidence_media_type IS NOT NULL
  AND evidence_size IS NOT NULL
  AND evidence_hash IS NOT NULL;

INSERT INTO experience_claim_processing (
    claim_id, evidence_version, attempt_count, next_attempt_at,
    lease_until, last_error, created_at, updated_at
)
SELECT id, evidence_version, 0, CURRENT_TIMESTAMP,
       NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM experience_claims
WHERE evidence_version > 0
  AND status IN ('PENDING', 'NEEDS_INFO');

UPDATE experience_claims
SET status = 'PROCESSING',
    ai_status = 'QUEUED',
    decision_explanation = 'Previously uploaded evidence is queued for analysis.',
    corroboration_status = 'NOT_INDEPENDENTLY_CORROBORATED'
WHERE evidence_version > 0
  AND status IN ('PENDING', 'NEEDS_INFO');

UPDATE experience_claims
SET status = 'AWAITING_EVIDENCE',
    ai_status = 'AWAITING_EVIDENCE',
    decision_explanation = 'Upload a supporting document to begin evidence analysis.'
WHERE evidence_version = 0
  AND status = 'PENDING';
