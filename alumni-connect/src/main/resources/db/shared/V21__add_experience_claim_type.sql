ALTER TABLE experience_claims
    ADD COLUMN claim_type VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED';

UPDATE experience_claims
SET claim_type = 'UNSPECIFIED'
WHERE claim_type IS NULL OR claim_type = '';
