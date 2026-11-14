ALTER TABLE experience_claim_evidence
    ADD COLUMN evidence_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE experience_claim_evidence
    ADD COLUMN relationship_type VARCHAR(32) NULL;

ALTER TABLE experience_claim_evidence
    ADD COLUMN related_evidence_id BIGINT NULL;

ALTER TABLE experience_claim_evidence
    ADD COLUMN superseded_at DATETIME NULL;

ALTER TABLE experience_claim_evidence
    ADD CONSTRAINT fk_experience_claim_evidence_related
        FOREIGN KEY (related_evidence_id) REFERENCES experience_claim_evidence(id) ON DELETE SET NULL;

CREATE INDEX idx_experience_claim_evidence_lifecycle
    ON experience_claim_evidence (claim_id, evidence_status, evidence_version);

UPDATE experience_claim_evidence
SET evidence_status = 'ACTIVE'
WHERE evidence_status IS NULL OR evidence_status = '';
