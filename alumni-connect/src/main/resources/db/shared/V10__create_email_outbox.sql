CREATE TABLE email_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    recipient VARCHAR(255) NOT NULL,
    encrypted_otp VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    next_attempt_at DATETIME NOT NULL,
    lease_until DATETIME,
    expires_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_email_outbox_due
    ON email_outbox (status, next_attempt_at, lease_until, expires_at);
CREATE INDEX idx_email_outbox_expiry
    ON email_outbox (expires_at);
