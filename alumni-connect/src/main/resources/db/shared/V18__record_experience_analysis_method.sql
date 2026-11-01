ALTER TABLE experience_claims
    ADD COLUMN analysis_method VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN';

ALTER TABLE experience_claims
    ADD COLUMN ocr_status VARCHAR(32) NOT NULL DEFAULT 'NOT_RUN';
