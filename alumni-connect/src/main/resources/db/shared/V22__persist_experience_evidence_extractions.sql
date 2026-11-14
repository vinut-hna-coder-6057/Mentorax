ALTER TABLE experience_claim_evidence
    ADD CONSTRAINT uk_experience_claim_evidence_id_version
        UNIQUE (id, evidence_version);

CREATE TABLE experience_evidence_extraction (
    id BIGINT NOT NULL AUTO_INCREMENT,
    evidence_id BIGINT NOT NULL,
    evidence_version INT NOT NULL,
    schema_version VARCHAR(64) NOT NULL,
    document_sha256 VARCHAR(64) NOT NULL,
    response_hash VARCHAR(64) NOT NULL,
    analysis_status VARCHAR(32) NOT NULL,
    document_classification VARCHAR(50) NOT NULL,
    classification_confidence DECIMAL(6,5) NOT NULL,
    ocr_status VARCHAR(32) NOT NULL,
    processed_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_extraction_evidence_version
        FOREIGN KEY (evidence_id, evidence_version)
        REFERENCES experience_claim_evidence (id, evidence_version) ON DELETE CASCADE,
    CONSTRAINT uk_experience_extraction_response
        UNIQUE (evidence_id, evidence_version, response_hash),
    CONSTRAINT uk_experience_extraction_id_evidence
        UNIQUE (id, evidence_id, evidence_version)
);

CREATE INDEX idx_experience_extraction_history
    ON experience_evidence_extraction (evidence_id, evidence_version, processed_at);

CREATE TABLE experience_evidence_extraction_field (
    id BIGINT NOT NULL AUTO_INCREMENT,
    extraction_id BIGINT NOT NULL,
    field_ordinal INT NOT NULL,
    field_name VARCHAR(64) NOT NULL,
    field_status VARCHAR(32) NOT NULL,
    extracted_value VARCHAR(500) NULL,
    confidence DECIMAL(6,5) NULL,
    model_confidence DECIMAL(6,5) NULL,
    extraction_method VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_extraction_field_run
        FOREIGN KEY (extraction_id) REFERENCES experience_evidence_extraction (id) ON DELETE CASCADE,
    CONSTRAINT uk_experience_extraction_field_ordinal
        UNIQUE (extraction_id, field_ordinal),
    CONSTRAINT uk_experience_extraction_field_id_run
        UNIQUE (id, extraction_id)
);

CREATE INDEX idx_experience_extraction_field_name
    ON experience_evidence_extraction_field (extraction_id, field_name, field_status);

CREATE TABLE experience_evidence_extraction_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    field_id BIGINT NOT NULL,
    candidate_ordinal INT NOT NULL,
    candidate_value VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_extraction_candidate_field
        FOREIGN KEY (field_id) REFERENCES experience_evidence_extraction_field (id) ON DELETE CASCADE,
    CONSTRAINT uk_experience_extraction_candidate_ordinal
        UNIQUE (field_id, candidate_ordinal)
);

CREATE TABLE experience_evidence_extraction_statement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    extraction_id BIGINT NOT NULL,
    statement_ordinal INT NOT NULL,
    statement_kind VARCHAR(32) NOT NULL,
    statement_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_extraction_statement_run
        FOREIGN KEY (extraction_id) REFERENCES experience_evidence_extraction (id) ON DELETE CASCADE,
    CONSTRAINT uk_experience_extraction_statement_ordinal
        UNIQUE (extraction_id, statement_ordinal),
    CONSTRAINT uk_experience_extraction_statement_id_run
        UNIQUE (id, extraction_id)
);

CREATE TABLE experience_evidence_extraction_provenance (
    id BIGINT NOT NULL AUTO_INCREMENT,
    extraction_id BIGINT NOT NULL,
    record_type VARCHAR(16) NOT NULL,
    field_id BIGINT NULL,
    statement_id BIGINT NULL,
    provenance_ordinal INT NOT NULL,
    source_evidence_id BIGINT NOT NULL,
    source_evidence_version INT NOT NULL,
    page_number INT NOT NULL,
    text_excerpt VARCHAR(500) NOT NULL,
    extraction_method VARCHAR(16) NOT NULL,
    source_confidence DECIMAL(6,5) NULL,
    bounding_box_x DECIMAL(8,6) NULL,
    bounding_box_y DECIMAL(8,6) NULL,
    bounding_box_width DECIMAL(8,6) NULL,
    bounding_box_height DECIMAL(8,6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_extraction_provenance_run
        FOREIGN KEY (extraction_id, source_evidence_id, source_evidence_version)
        REFERENCES experience_evidence_extraction (id, evidence_id, evidence_version) ON DELETE CASCADE,
    CONSTRAINT fk_experience_extraction_provenance_field
        FOREIGN KEY (field_id, extraction_id)
        REFERENCES experience_evidence_extraction_field (id, extraction_id),
    CONSTRAINT fk_experience_extraction_provenance_statement
        FOREIGN KEY (statement_id, extraction_id)
        REFERENCES experience_evidence_extraction_statement (id, extraction_id),
    CONSTRAINT chk_experience_extraction_provenance_target
        CHECK (
            (record_type = 'CLASSIFICATION' AND field_id IS NULL AND statement_id IS NULL)
            OR (record_type = 'FIELD' AND field_id IS NOT NULL AND statement_id IS NULL)
            OR (record_type = 'STATEMENT' AND field_id IS NULL AND statement_id IS NOT NULL)
        ),
    CONSTRAINT uk_experience_extraction_provenance_ordinal
        UNIQUE (extraction_id, provenance_ordinal)
);

CREATE INDEX idx_experience_extraction_provenance_page
    ON experience_evidence_extraction_provenance
        (extraction_id, source_evidence_id, source_evidence_version, page_number);

CREATE TABLE experience_evidence_extraction_warning (
    id BIGINT NOT NULL AUTO_INCREMENT,
    extraction_id BIGINT NOT NULL,
    warning_ordinal INT NOT NULL,
    warning_text VARCHAR(500) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_extraction_warning_run
        FOREIGN KEY (extraction_id) REFERENCES experience_evidence_extraction (id) ON DELETE CASCADE,
    CONSTRAINT uk_experience_extraction_warning_ordinal
        UNIQUE (extraction_id, warning_ordinal)
);
