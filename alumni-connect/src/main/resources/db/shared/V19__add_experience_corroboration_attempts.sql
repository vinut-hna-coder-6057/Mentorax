CREATE TABLE experience_corroboration_attempts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    provider_id VARCHAR(100) NOT NULL,
    verification_method VARCHAR(100) NOT NULL,
    request_at DATETIME NOT NULL,
    response_at DATETIME NULL,
    result VARCHAR(32) NOT NULL,
    reference_identifier VARCHAR(255) NULL,
    failure_reason VARCHAR(500) NULL,
    independently_authenticated BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_experience_corroboration_attempt_claim
        FOREIGN KEY (claim_id) REFERENCES experience_claims(id) ON DELETE CASCADE
);
