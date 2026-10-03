ALTER TABLE email_outbox
    ADD COLUMN purpose VARCHAR(32) NOT NULL DEFAULT 'EMAIL_VERIFICATION';

CREATE INDEX idx_email_outbox_recipient_purpose
    ON email_outbox (recipient, purpose);
