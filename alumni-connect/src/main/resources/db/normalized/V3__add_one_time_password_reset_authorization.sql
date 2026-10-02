ALTER TABLE otp_verifications
    ADD COLUMN reset_token_hash VARCHAR(64) NULL;

ALTER TABLE otp_verifications
    ADD COLUMN reset_token_expiry DATETIME NULL;

CREATE UNIQUE INDEX uk_otp_reset_token_hash
    ON otp_verifications (reset_token_hash);
